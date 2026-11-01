package de.lulurobb.keypassmobile.platform.session

import de.lulurobb.keypassmobile.core.index.EntryIndex
import de.lulurobb.keypassmobile.core.settings.PasswordExpiry
import de.lulurobb.keypassmobile.core.sync.BackgroundSyncResult
import de.lulurobb.keypassmobile.core.sync.CandidateRejection
import de.lulurobb.keypassmobile.core.sync.KeySource
import de.lulurobb.keypassmobile.core.sync.RefreshResult
import de.lulurobb.keypassmobile.core.sync.RemoteSource
import de.lulurobb.keypassmobile.core.sync.SyncEngine
import de.lulurobb.keypassmobile.core.sync.UnlockResult
import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.platform.keystore.HashStore
import de.lulurobb.keypassmobile.platform.settings.SettingsRepository
import de.lulurobb.keypassmobile.platform.settings.TrustedServer
import de.lulurobb.keypassmobile.platform.smb.ServerNames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

/** A [RemoteSource] for one sync call; closed afterwards (one SMB connection per stat and download). */
interface RemoteHandle : RemoteSource, Closeable

/** Creates the remote for one sync call; null when no source is configured. Must not throw. */
fun interface RemoteFactory {
    fun open(): RemoteHandle?
}

/** Android side effects of state changes (foreground service, clipboard). */
interface SessionEffects {
    fun onUnlocked()

    fun onLocked(reason: LockReason)
}

/**
 * The unlocked state of the process (section 8: UI, autofill and sync share
 * one process). Pure Kotlin on top of [SyncEngine]; the Android parts come in
 * through [HashStore], [SessionEffects] and [RemoteFactory].
 *
 * While unlocked it holds the [Vault], its [EntryIndex] and the password hash
 * (needed for the refresh after unlock, SY-04). [lock] closes the vault,
 * drops the index and overwrites the hash (SE-04).
 *
 * All suspend functions run their work in [scope], so a cancelled caller
 * (e.g. a closed screen) never leaves a half finished state transition.
 *
 * @param elapsedMillis monotonic clock that keeps running in deep sleep
 *   (SystemClock.elapsedRealtime on Android), for the idle lock (SE-02).
 */
internal class Session(
    private val engine: SyncEngine,
    private val hashStore: HashStore,
    private val settings: SettingsRepository,
    private val remotes: RemoteFactory,
    private val effects: SessionEffects,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
    private val elapsedMillis: () -> Long,
    private val hasher: (CharArray) -> ByteArray,
    private val indexer: (Vault) -> EntryIndex = ::EntryIndex,
    private val preload: () -> Unit = {},
) {
    private val expiry = PasswordExpiry(clock)

    /** Guards [hash], [generation], [idleJob] and transitions of [mutableState]. */
    private val stateLock = Any()

    private val mutableState = MutableStateFlow<SessionState>(SessionState.Locked(null))
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    /** SHA-256 of the master password while unlocked; overwritten on lock (SE-04). */
    private var hash: ByteArray? = null

    /** Changes at every unlock start and every lock, so late results of an old attempt are discarded. Written under [stateLock]. */
    @Volatile
    private var generation = 0

    private var idleJob: Job? = null

    @Volatile
    private var lastActivity = 0L

    private val syncMutex = Mutex()
    private val syncRunning = AtomicInteger()
    private val mutableSync = MutableStateFlow(SyncInfo(engine.status(), syncing = false, serverChange = null))
    val sync: StateFlow<SyncInfo> = mutableSync.asStateFlow()

    /** Last store of the hash for the fingerprint, for tests. */
    @Volatile
    internal var storeJob: Job? = null
        private set

    init {
        publishSync()
    }

    // ---- unlock ----

    /**
     * What the unlock screen offers first. When the master password is due
     * (EN-05) the stored value is deleted here.
     */
    fun unlockOffer(): UnlockOffer {
        val reason = fingerprintBlocker() ?: return UnlockOffer.Fingerprint
        return UnlockOffer.Password(reason)
    }

    /** True when the fingerprint can unlock now ("Doch mit Fingerabdruck"). */
    fun isFingerprintUsable(): Boolean = fingerprintBlocker() == null

    /**
     * Null when the fingerprint path may be tried, else why not. Deletes the
     * stored value when the password is due.
     */
    internal fun fingerprintBlocker(): PasswordRequiredReason? {
        val s = settings.settings.value
        if (!s.fingerprintEnabled || !hashStore.hasStoredValue()) return PasswordRequiredReason.NOT_SET_UP
        if (expiry.isDue(s.lastPasswordEntry, s.intervalAtEntry, s.passwordInterval)) {
            scope.launch(io) { hashStore.clear() }
            return PasswordRequiredReason.DUE
        }
        return null
    }

    /**
     * Unlocks with the typed master password (EN-01). [password] is wiped.
     * [enableFingerprint] non-null sets the fingerprint switch first (setup
     * step 3). With the fingerprint on, the hash is stored with a new key that
     * expires after the current interval (EN-05), and the entry time is recorded.
     */
    suspend fun unlockWithPassword(password: CharArray, enableFingerprint: Boolean? = null): UnlockOutcome {
        val gen = beginUnlock()
        if (gen == null) {
            password.fill('\u0000')
            return busyOutcome()
        }
        return scope.async {
            val hash = try {
                withContext(io) { hasher(password) }
            } catch (e: Exception) {
                failUnlock(gen, null)
                throw e
            } finally {
                password.fill('\u0000')
            }
            val result = withContext(io) { engine.unlock(hash, KeySource.TYPED_PASSWORD) }
            when (result) {
                is UnlockResult.Opened -> {
                    val renewedDays = afterPasswordEntry(hash, enableFingerprint)
                    finishOpened(gen, result, hash, KeySource.TYPED_PASSWORD, renewedDays)
                }
                is UnlockResult.WrongKey -> failUnlock(gen, hash, UnlockOutcome.WrongPassword)
                UnlockResult.NoDatabase -> failUnlock(gen, hash, UnlockOutcome.NoDatabase)
                UnlockResult.Corrupt -> failUnlock(gen, hash, UnlockOutcome.Corrupt)
            }
        }.await()
    }

    /**
     * Unlocks with the hash released by the fingerprint (EN-03). Takes
     * ownership of [hash]. If it opens no file (EN-06), the stored value is
     * deleted and the password is asked for.
     */
    suspend fun unlockWithStoredHash(hash: ByteArray): UnlockOutcome {
        val gen = beginUnlock()
        if (gen == null) {
            hash.fill(0)
            return busyOutcome()
        }
        return scope.async {
            val result = withContext(io) { engine.unlock(hash, KeySource.FINGERPRINT) }
            when (result) {
                is UnlockResult.Opened -> finishOpened(gen, result, hash, KeySource.FINGERPRINT, renewedDays = null)
                is UnlockResult.WrongKey -> {
                    withContext(io) { hashStore.clear() }
                    failUnlock(gen, hash, UnlockOutcome.PasswordRequired(PasswordRequiredReason.STORED_VALUE_REJECTED))
                }
                UnlockResult.NoDatabase -> failUnlock(gen, hash, UnlockOutcome.NoDatabase)
                UnlockResult.Corrupt -> failUnlock(gen, hash, UnlockOutcome.Corrupt)
            }
        }.await()
    }

    /** Switches to [SessionState.Unlocking]; null when already unlocking or unlocked. */
    private fun beginUnlock(): Int? = synchronized(stateLock) {
        if (mutableState.value !is SessionState.Locked) return null
        generation++
        mutableState.value = SessionState.Unlocking
        generation
    }

    private fun busyOutcome(): UnlockOutcome = when (state.value) {
        is SessionState.Unlocked -> UnlockOutcome.Unlocked(newVersionOpened = false, candidateRejected = null, fingerprintRenewedForDays = null)
        else -> UnlockOutcome.Busy
    }

    private fun failUnlock(gen: Int, hash: ByteArray?, outcome: UnlockOutcome = UnlockOutcome.Aborted): UnlockOutcome {
        hash?.fill(0)
        synchronized(stateLock) {
            if (gen == generation && mutableState.value === SessionState.Unlocking) {
                mutableState.value = SessionState.Locked(null)
            }
        }
        return outcome
    }

    /** Records the password entry and stores the hash for the fingerprint. Returns the days it is valid for, or null. */
    private fun afterPasswordEntry(hash: ByteArray, enableFingerprint: Boolean?): Int? {
        val now = clock.instant()
        val s = settings.update {
            it.copy(
                fingerprintEnabled = enableFingerprint ?: it.fingerprintEnabled,
                lastPasswordEntry = now,
                intervalAtEntry = it.passwordInterval,
            )
        }
        if (!s.fingerprintEnabled) {
            if (enableFingerprint == false) scope.launch(io) { hashStore.clear() }
            return null
        }
        val copy = hash.copyOf()
        val validityEnd = expiry.keyValidityEnd(now, s.passwordInterval)
        storeJob = scope.launch(io) {
            try {
                hashStore.store(copy, validityEnd)
            } catch (_: Exception) {
                // Fingerprint stays unavailable until the next password entry; unlockOffer() shows it.
            } finally {
                copy.fill(0)
            }
        }
        return s.passwordInterval.days
    }

    private suspend fun finishOpened(
        gen: Int,
        result: UnlockResult.Opened,
        hash: ByteArray,
        keySource: KeySource,
        renewedDays: Int?,
    ): UnlockOutcome {
        val index = try {
            withContext(io) { indexer(result.vault) }
        } catch (e: Exception) {
            result.vault.close()
            failUnlock(gen, hash)
            throw e
        }
        val unlocked = SessionState.Unlocked(
            vault = result.vault,
            index = index,
            keySource = keySource,
            newVersionOpened = result.newVersionOpened,
            newVersionRejected = result.candidateRejected == CandidateRejection.WRONG_KEY,
            version = 0,
        )
        val published = synchronized(stateLock) {
            if (gen != generation || mutableState.value !== SessionState.Unlocking) {
                false
            } else {
                this.hash = hash
                lastActivity = elapsedMillis()
                mutableState.value = unlocked
                startIdleWatch(gen)
                true
            }
        }
        if (!published) {
            // Locked while decrypting (e.g. swiped away): discard everything.
            result.vault.close()
            hash.fill(0)
            return UnlockOutcome.Aborted
        }
        effects.onUnlocked()
        scope.launch(io) {
            try {
                preload()
            } catch (_: Exception) {
                // Matching falls back to loading the list on first use.
            }
        }
        publishSync()
        // SY-04: check the share in the background; the unlock never waits for it.
        scope.launch { syncNow() }
        return UnlockOutcome.Unlocked(result.newVersionOpened, result.candidateRejected, renewedDays)
    }

    // ---- lock and idle ----

    /**
     * Locks: closes the vault, drops the index, overwrites the hash, cancels
     * the idle timer; the effects clear our clipboard content and stop the
     * foreground service. Safe from any thread; no-op when already locked.
     */
    fun lock(reason: LockReason) {
        val vault: Vault?
        synchronized(stateLock) {
            val current = mutableState.value
            if (current is SessionState.Locked) return
            generation++
            vault = (current as? SessionState.Unlocked)?.vault
            hash?.fill(0)
            hash = null
            idleJob?.cancel()
            idleJob = null
            mutableState.value = SessionState.Locked(reason)
        }
        try {
            vault?.close()
        } catch (_: Exception) {
            // Closing only drops references.
        }
        effects.onLocked(reason)
        publishSync()
    }

    /** Marks user activity (SE-02): app actions, autofill requests, clipboard copies. */
    fun touch() {
        lastActivity = elapsedMillis()
    }

    /**
     * Locks now if the idle time is over. Called before vault access and when
     * the screen turns on, because timers do not run in deep sleep.
     * Returns true if it locked.
     */
    fun checkIdle(): Boolean {
        if (state.value !is SessionState.Unlocked) return false
        val timeout = settings.settings.value.idleTimeout
        if (elapsedMillis() - lastActivity < timeout.duration.toMillis()) return false
        lock(LockReason.Idle(timeout))
        return true
    }

    /** Caller holds [stateLock]. */
    private fun startIdleWatch(gen: Int) {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (true) {
                if (gen != generation || state.value !is SessionState.Unlocked) return@launch
                val timeout = settings.settings.value.idleTimeout
                val remaining = lastActivity + timeout.duration.toMillis() - elapsedMillis()
                if (remaining <= 0) {
                    lock(LockReason.Idle(timeout))
                    return@launch
                }
                // Short ticks: delay() does not count deep sleep, elapsedMillis() does.
                delay(remaining.coerceAtMost(MAX_IDLE_TICK_MILLIS))
            }
        }
    }

    /** The unlocked state after an idle check, or null. */
    fun unlockedOrNull(): SessionState.Unlocked? {
        checkIdle()
        return state.value as? SessionState.Unlocked
    }

    // ---- sync ----

    /**
     * "Jetzt synchronisieren" (SY-07): unlocked like SY-04 (check, download,
     * open with the key in memory, show the new version), locked like SY-06
     * (download as candidate). Also used after unlock and by the worker.
     * Runs are serialized.
     */
    suspend fun syncNow(): SyncResult = runSync { remotes.open() }

    /** Like [syncNow], but with [remote] instead of the configured source (setup, DS-02). Does not close it. */
    internal suspend fun syncWith(remote: RemoteSource): SyncResult = runSync {
        object : RemoteHandle, RemoteSource by remote {
            override fun close() = Unit
        }
    }

    private suspend fun runSync(remote: () -> RemoteHandle?): SyncResult = scope.async {
        syncMutex.withLock {
            syncRunning.incrementAndGet()
            publishSync()
            try {
                val (gen, hashCopy) = synchronized(stateLock) {
                    if (state.value is SessionState.Unlocked) generation to hash?.copyOf() else generation to null
                }
                if (hashCopy != null) refresh(gen, hashCopy, remote) else backgroundSync(remote)
            } finally {
                syncRunning.decrementAndGet()
                publishSync()
            }
        }
    }.await()

    /** SY-04 with a copy of the hash, so a lock during the download cannot wipe it mid-use. */
    private suspend fun refresh(gen: Int, hashCopy: ByteArray, remote: () -> RemoteHandle?): SyncResult {
        val result = try {
            withContext(io) {
                remote()?.use { engine.refreshAfterUnlock(it, hashCopy) }
            }
        } finally {
            hashCopy.fill(0)
        } ?: return SyncResult.NotConfigured
        return when (result) {
            RefreshResult.UpToDate -> SyncResult.UpToDate
            is RefreshResult.Updated -> {
                replaceVault(gen, result.vault)
                SyncResult.Updated
            }
            RefreshResult.NewVersionRejected -> {
                synchronized(stateLock) {
                    val current = mutableState.value
                    if (gen == generation && current is SessionState.Unlocked) mutableState.value = current.rejected()
                }
                SyncResult.NewVersionRejected
            }
            is RefreshResult.Failed -> SyncResult.Failed(result.problem)
        }
    }

    private suspend fun backgroundSync(remote: () -> RemoteHandle?): SyncResult {
        val result = withContext(io) {
            remote()?.use { engine.backgroundSync(it) }
        } ?: return SyncResult.NotConfigured
        return when (result) {
            BackgroundSyncResult.UpToDate -> SyncResult.UpToDate
            BackgroundSyncResult.CandidateDownloaded, BackgroundSyncResult.CandidateAlreadyPresent -> SyncResult.CandidateReady
            is BackgroundSyncResult.Failed -> SyncResult.Failed(result.problem)
        }
    }

    /** Shows the newer [vault] and closes the previous one; closes [vault] if locked meanwhile. */
    private suspend fun replaceVault(gen: Int, vault: Vault) {
        val index = try {
            withContext(io) { indexer(vault) }
        } catch (_: Exception) {
            vault.close()
            return
        }
        val previous = synchronized(stateLock) {
            val current = mutableState.value
            if (gen != generation || current !is SessionState.Unlocked) {
                null
            } else {
                mutableState.value = current.with(vault, index)
                current.vault
            }
        }
        if (previous == null) vault.close() else previous.close()
    }

    /** Accepts the server GUID a sync saw instead of the remembered one (DS-04), then syncs. */
    suspend fun trustPendingServer(): SyncResult {
        val now = clock.instant()
        settings.update { s ->
            val pending = s.pendingServerGuid ?: return@update s
            s.copy(trustedServer = TrustedServer(pending, now), pendingServerGuid = null)
        }
        publishSync()
        return syncNow()
    }

    /** Recomputes [sync] from the engine and the settings. Cheap; any thread. */
    fun publishSync() {
        val s = settings.settings.value
        val pending = s.pendingServerGuid
        val source = s.source
        val change = if (pending != null && source != null) {
            val remembered = s.trustedServer?.guid
            ServerChange(
                host = source.host,
                rememberedGuid = remembered,
                rememberedName = remembered?.let(ServerNames::fromGuid),
                newGuid = pending,
                newName = ServerNames.fromGuid(pending),
            )
        } else {
            null
        }
        mutableSync.value = SyncInfo(engine.status(), syncing = syncRunning.get() > 0, serverChange = change)
    }

    // ---- settings with side effects ----

    /**
     * Switches the fingerprint unlock (UI-03). Off deletes the stored value.
     * On stores the hash in memory right away when unlocked and the last
     * password entry is still within its interval (key validity: that entry
     * plus the interval), else at the next password entry.
     */
    suspend fun setFingerprintEnabled(enabled: Boolean): FingerprintSetupResult = scope.async {
        if (!enabled) {
            settings.update { it.copy(fingerprintEnabled = false) }
            withContext(io) { hashStore.clear() }
            return@async FingerprintSetupResult.DISABLED
        }
        val s = settings.update { it.copy(fingerprintEnabled = true) }
        val entry = s.lastPasswordEntry
        val atEntry = s.intervalAtEntry
        if (entry == null || atEntry == null || expiry.isDue(entry, atEntry, s.passwordInterval)) {
            return@async FingerprintSetupResult.ON_NEXT_PASSWORD
        }
        val copy = synchronized(stateLock) { hash?.copyOf() } ?: return@async FingerprintSetupResult.ON_NEXT_PASSWORD
        try {
            withContext(io) { hashStore.store(copy, expiry.dueAt(entry, atEntry, s.passwordInterval)) }
            FingerprintSetupResult.STORED
        } catch (_: Exception) {
            FingerprintSetupResult.FAILED
        } finally {
            copy.fill(0)
        }
    }.await()

    internal companion object {
        const val MAX_IDLE_TICK_MILLIS = 5_000L
    }
}
