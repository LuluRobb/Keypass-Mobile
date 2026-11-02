package de.lulurobb.keypassmobile.platform.session

import de.lulurobb.keypassmobile.core.index.EntryIndex
import de.lulurobb.keypassmobile.core.settings.PasswordExpiry
import de.lulurobb.keypassmobile.core.sync.BackgroundSyncResult
import de.lulurobb.keypassmobile.core.sync.CandidateRejection
import de.lulurobb.keypassmobile.core.sync.CandidateState
import de.lulurobb.keypassmobile.core.sync.KeySource
import de.lulurobb.keypassmobile.core.sync.RefreshResult
import de.lulurobb.keypassmobile.core.sync.RemoteSource
import de.lulurobb.keypassmobile.core.sync.SyncEngine
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.core.sync.UnlockResult
import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.platform.keystore.HashStore
import de.lulurobb.keypassmobile.platform.settings.SettingsRepository
import de.lulurobb.keypassmobile.platform.settings.TrustedServer
import de.lulurobb.keypassmobile.platform.smb.ServerNames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A [RemoteSource] for one sync call; closed afterwards (one SMB connection per stat and download). */
interface RemoteHandle : RemoteSource, Closeable

/** Creates the remote for one sync call; null when no source is configured. Must not throw. */
fun interface RemoteFactory {
    fun open(): RemoteHandle?
}

/** Android side effects of state changes (foreground service, clipboard). */
interface SessionEffects {
    fun onUnlocked()

    /** The session is about to lock; still unlocked. Clear secrets outside the session (clipboard) here. */
    fun onLocking() = Unit

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
    private val journal: SwitchJournal = SwitchJournal.None,
    private val clearSmbPassword: () -> Unit = {},
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

    private val reportCounter = AtomicLong()

    /**
     * One event per finished sync run (also the automatic one after an unlock,
     * SY-04). Not replayed: only collectors that are active when the run ends
     * get it. Use [lastSyncReport] when no event may be missed.
     */
    private val mutableReports = MutableSharedFlow<SyncReport>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val syncReports: SharedFlow<SyncReport> = mutableReports.asSharedFlow()

    private val mutableLastReport = MutableStateFlow<SyncReport?>(null)

    /**
     * The newest [SyncReport], null before the first run. Its [SyncReport.id]
     * grows with every run, so the UI remembers the last id it showed and shows
     * each report once, also when it starts collecting only after the run ended.
     */
    val lastSyncReport: StateFlow<SyncReport?> = mutableLastReport.asStateFlow()

    /** Serializes stores and deletions of the hash for the fingerprint (EN-05 cleanup vs. a fresh store). */
    private val fingerprintMutex = Mutex()

    /** Last store of the hash for the fingerprint (true when stored), for tests. */
    @Volatile
    internal var storeJob: Deferred<Boolean>? = null
        private set

    /** A running store of the hash for the fingerprint, valid for [days] days when it succeeds. */
    private class PendingStore(val days: Int, val result: Deferred<Boolean>)

    /** Source changes running (or to be recovered); unlocks return Busy meanwhile. Guarded by [stateLock]. */
    private var switching = 0

    /** A source change was interrupted by a process death; see [recoverInterruptedSwitch]. Guarded by [stateLock]. */
    private var recoveryPending = false

    init {
        recoveryPending = try {
            journal.read() != null
        } catch (_: Exception) {
            true
        }
        if (recoveryPending) switching = 1
        publishSync()
    }

    // ---- unlock ----

    /**
     * What the unlock screen offers first. When the master password is due
     * (EN-05) the stored value is deleted here ([expireFingerprint]), before
     * the answer; the reason [PasswordRequiredReason.DUE] stays until the next
     * typed password.
     */
    suspend fun unlockOffer(): UnlockOffer {
        expireFingerprint()
        val reason = fingerprintBlocker() ?: return UnlockOffer.Fingerprint
        return UnlockOffer.Password(reason)
    }

    /** True when the fingerprint can unlock now ("Doch mit Fingerabdruck"). Free of side effects. */
    fun isFingerprintUsable(): Boolean = fingerprintBlocker() == null

    /**
     * Null when the fingerprint path may be tried, else why not. Free of side
     * effects: the stored value of a due password is deleted by [expireFingerprint].
     */
    internal fun fingerprintBlocker(): PasswordRequiredReason? {
        val s = settings.settings.value
        if (!s.fingerprintEnabled) return PasswordRequiredReason.NOT_SET_UP
        if (!hashStore.hasStoredValue()) {
            // After the cleanup of a due password the reason stays visible until the next password entry.
            return if (s.fingerprintExpired) PasswordRequiredReason.DUE else PasswordRequiredReason.NOT_SET_UP
        }
        val due = expiry.isDue(s.lastPasswordEntry, s.intervalAtEntry, s.passwordInterval)
        return if (due) PasswordRequiredReason.DUE else null
    }

    /**
     * EN-05: deletes the stored value when the master password is due. Called
     * by [unlockOffer] and before each fingerprint unlock, the places where
     * the stored value would otherwise be offered. Returns true when it deletes.
     * Runs under the same lock as the store, and decides again inside it, so it
     * never deletes a hash that a typed password has just stored.
     */
    internal suspend fun expireFingerprint(): Boolean = scope.async {
        withContext(io) {
            fingerprintMutex.withLock {
                val entry = settings.settings.value.lastPasswordEntry
                if (hashStore.hasStoredValue() && fingerprintBlocker() == PasswordRequiredReason.DUE) {
                    hashStore.clear()
                    markExpired(entry)
                    true
                } else {
                    false
                }
            }
        }
    }.await()

    /**
     * Deletes the stored value, serialized with every store. [expired]: it is
     * deleted because the password is due, so the unlock screen keeps reporting
     * [PasswordRequiredReason.DUE] until the next typed password.
     */
    internal suspend fun clearStoredHash(expired: Boolean = false) {
        withContext(io) {
            fingerprintMutex.withLock {
                val entry = settings.settings.value.lastPasswordEntry
                hashStore.clear()
                if (expired) markExpired(entry)
            }
        }
    }

    /**
     * Sets the expired notice, unless a typed password renewed the entry time since
     * [entry] was read (that write is not under [fingerprintMutex]): the notice must
     * never outlive the password entry that ends it.
     */
    private fun markExpired(entry: Instant?) {
        settings.update { if (it.lastPasswordEntry == entry) it.copy(fingerprintExpired = true) else it }
    }

    /**
     * Unlocks with the typed master password (EN-01). [password] is wiped.
     * [enableFingerprint] non-null sets the fingerprint switch first (setup
     * step 3). With the fingerprint on, the hash is stored with a new key that
     * expires after the current interval (EN-05), and the entry time is recorded.
     *
     * Returns only after the store of the hash finished, so the outcome tells
     * the truth about the fingerprint ([UnlockOutcome.Unlocked.fingerprintStoreFailed]).
     * The outcome is a hint for messages; [state] is the source of truth for
     * navigation: the session may already be locked again when it arrives
     * (then the outcome is [UnlockOutcome.Aborted] and [SessionState.Locked]
     * says why).
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
            } catch (e: Throwable) {
                failUnlock(gen, null)
                throw e
            } finally {
                password.fill('\u0000')
            }
            attemptUnlock(
                gen, hash, KeySource.TYPED_PASSWORD,
                afterOpen = { afterPasswordEntry(hash, enableFingerprint) },
                onWrongKey = { UnlockOutcome.WrongPassword },
            )
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
            attemptUnlock(
                gen, hash, KeySource.FINGERPRINT,
                afterOpen = { null },
                onWrongKey = {
                    clearStoredHash()
                    UnlockOutcome.PasswordRequired(PasswordRequiredReason.STORED_VALUE_REJECTED)
                },
            )
        }.await()
    }

    /**
     * Opens the local file with [hash] and publishes the result. Whatever fails
     * before the session owns vault and hash ([finishOpened] publishing them),
     * the vault is closed, the hash wiped and the state is no longer Unlocking.
     */
    private suspend fun attemptUnlock(
        gen: Int,
        hash: ByteArray,
        keySource: KeySource,
        afterOpen: suspend () -> PendingStore?,
        onWrongKey: suspend () -> UnlockOutcome,
    ): UnlockOutcome {
        var vault: Vault? = null
        try {
            return when (val result = withContext(io) { engine.unlock(hash, keySource) }) {
                is UnlockResult.Opened -> {
                    vault = result.vault
                    val pendingStore = afterOpen()
                    finishOpened(gen, result, hash, keySource, pendingStore)
                }
                is UnlockResult.WrongKey -> failUnlock(gen, hash, onWrongKey())
                UnlockResult.NoDatabase -> failUnlock(gen, hash, UnlockOutcome.NoDatabase)
                UnlockResult.Corrupt -> failUnlock(gen, hash, UnlockOutcome.Corrupt)
            }
        } catch (e: Throwable) {
            if (!synchronized(stateLock) { this.hash === hash }) {
                try {
                    vault?.close()
                } catch (_: Exception) {
                    // Closing only drops references.
                }
                failUnlock(gen, hash)
            }
            throw e
        }
    }

    /** Switches to [SessionState.Unlocking]; null when already unlocking or unlocked. */
    private fun beginUnlock(): Int? = synchronized(stateLock) {
        if (mutableState.value !is SessionState.Locked || switching > 0) return null
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

    /**
     * Records the password entry and starts storing the hash for the
     * fingerprint, in parallel to the rest of the unlock. Null when the
     * fingerprint is off. [finishOpened] waits for the result.
     */
    private suspend fun afterPasswordEntry(hash: ByteArray, enableFingerprint: Boolean?): PendingStore? {
        val now = clock.instant()
        val s = settings.update {
            it.copy(
                fingerprintEnabled = enableFingerprint ?: it.fingerprintEnabled,
                lastPasswordEntry = now,
                intervalAtEntry = it.passwordInterval,
                fingerprintExpired = false,
            )
        }
        if (!s.fingerprintEnabled) {
            if (enableFingerprint == false) clearStoredHash()
            return null
        }
        val copy = hash.copyOf()
        val validityEnd = expiry.keyValidityEnd(now, s.passwordInterval)
        val job = scope.async(io) {
            try {
                fingerprintMutex.withLock { hashStore.store(copy, validityEnd) }
                true
            } catch (_: Exception) {
                // Fingerprint stays unavailable until the next password entry; unlockOffer() shows it,
                // and the outcome says so (fingerprintStoreFailed).
                false
            } finally {
                copy.fill(0)
            }
        }
        storeJob = job
        return PendingStore(s.passwordInterval.days, job)
    }

    private suspend fun finishOpened(
        gen: Int,
        result: UnlockResult.Opened,
        hash: ByteArray,
        keySource: KeySource,
        fingerprintStore: PendingStore?,
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
        // The effects may lock right away (foreground service refused): then nothing more starts.
        if (!isCurrent(gen)) return UnlockOutcome.Aborted
        scope.launch(io) {
            try {
                preload()
            } catch (_: Exception) {
                // Matching falls back to loading the list on first use.
            }
        }
        publishSync()
        // SY-04: check the share in the background; the unlock never waits for it.
        scope.launch { syncNow(SyncTrigger.AFTER_UNLOCK) }
        // The store ran in parallel to the indexing above; the outcome only claims what really happened.
        val renewedDays = fingerprintStore?.takeIf { it.result.await() }?.days
        if (!isCurrent(gen)) return UnlockOutcome.Aborted
        return UnlockOutcome.Unlocked(
            newVersionOpened = result.newVersionOpened,
            candidateRejected = result.candidateRejected,
            fingerprintRenewedForDays = renewedDays,
            fingerprintStoreFailed = fingerprintStore != null && renewedDays == null,
        )
    }

    /** True while the unlock of [gen] is published and not locked again. */
    private fun isCurrent(gen: Int): Boolean =
        synchronized(stateLock) { gen == generation && mutableState.value is SessionState.Unlocked }

    // ---- lock and idle ----

    /**
     * Locks: closes the vault, drops the index, overwrites the hash, cancels
     * the idle timer; the effects clear our clipboard content and stop the
     * foreground service. Safe from any thread; no-op when already locked.
     */
    fun lock(reason: LockReason) {
        // Secrets outside the session (clipboard) go first, before the state says Locked.
        if (mutableState.value !is SessionState.Locked) {
            try {
                effects.onLocking()
            } catch (_: Exception) {
                // The lock must happen whatever the effects do.
            }
        }
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
        try {
            effects.onLocked(reason)
        } catch (_: Exception) {
            // The state is Locked already; the rest must still run.
        }
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
     * Runs are serialized. Every finished run is also published in [syncReports]
     * with [trigger].
     */
    suspend fun syncNow(trigger: SyncTrigger = SyncTrigger.REQUESTED): SyncResult = runSync(trigger) { remotes.open() }

    /** Like [syncNow], but with [remote] instead of the configured source (setup, DS-02). Does not close it. */
    internal suspend fun syncWith(remote: RemoteSource): SyncResult = runSync(SyncTrigger.SETUP) {
        object : RemoteHandle, RemoteSource by remote {
            override fun close() = Unit
        }
    }

    private suspend fun runSync(trigger: SyncTrigger, remote: () -> RemoteHandle?): SyncResult = scope.async {
        syncMutex.withLock { syncLocked(trigger, remote = remote) }
    }.await()

    /** One sync run and its report. The caller holds [syncMutex]. */
    private suspend fun syncLocked(trigger: SyncTrigger, report: Boolean = true, remote: () -> RemoteHandle?): SyncResult {
        syncRunning.incrementAndGet()
        publishSync()
        val result = try {
            val (gen, hashCopy) = synchronized(stateLock) {
                if (state.value is SessionState.Unlocked) generation to hash?.copyOf() else generation to null
            }
            if (hashCopy != null) refresh(gen, hashCopy, remote) else backgroundSync(remote)
        } finally {
            syncRunning.decrementAndGet()
            publishSync()
        }
        if (report) emitReport(trigger, result)
        return result
    }

    private fun emitReport(trigger: SyncTrigger, result: SyncResult) {
        val report = SyncReport(reportCounter.incrementAndGet(), trigger, result)
        mutableLastReport.value = report
        mutableReports.tryEmit(report)
    }

    /** SY-04 with a copy of the hash, so a lock during the download cannot wipe it mid-use. */
    private suspend fun refresh(gen: Int, hashCopy: ByteArray, remote: () -> RemoteHandle?): SyncResult {
        var index: EntryIndex? = null
        val result = try {
            withContext(io) {
                // The index is built before the new file replaces the offline copy: if it fails, nothing changed.
                remote()?.use { engine.refreshAfterUnlock(it, hashCopy) { vault -> index = indexer(vault) } }
            }
        } finally {
            hashCopy.fill(0)
        } ?: return SyncResult.NotConfigured
        return when (result) {
            RefreshResult.UpToDate -> SyncResult.UpToDate
            is RefreshResult.Updated -> replaceVault(gen, result.vault, index!!)
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

    /**
     * Shows the newer [vault] (with its [index], built before the engine stored
     * the file; a failing indexer makes the refresh fail as [SyncProblem.INVALID_FILE]
     * with the old copy untouched) and closes the previous one. The newer file is
     * the offline copy already. Reports [SyncResult.Updated] only when it is
     * shown; when the session was locked meanwhile [vault] is closed and the
     * result is [SyncResult.CandidateReady] (the next unlock opens it).
     */
    private fun replaceVault(gen: Int, vault: Vault, index: EntryIndex): SyncResult {
        val previous = synchronized(stateLock) {
            val current = mutableState.value
            if (gen != generation || current !is SessionState.Unlocked) {
                null
            } else {
                mutableState.value = current.with(vault, index)
                current.vault
            }
        }
        if (previous == null) {
            vault.close()
            return SyncResult.CandidateReady
        }
        previous.close()
        return SyncResult.Updated
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

    /**
     * Setup and source change as one unit (DS-02): the test download as sync run
     * (reported as [SyncTrigger.SETUP] only when the change succeeds), setting the
     * old offline copy aside ([SyncEngine.markSourceChanged], SY-05), then
     * [SourceSwitch.stage] (the new SMB password) and [SourceSwitch.commit] (the
     * new source) right after each other, so the password never belongs to another
     * source for longer than that. It runs in the session's scope under
     * [syncMutex], so it finishes even if the caller is cancelled, no other sync
     * (worker, refresh after unlock) reads the old source in between, and no unlock
     * can start meanwhile (they return [UnlockOutcome.Busy]).
     *
     * On any failure the previous password, offline copy and source are back and
     * the foreign candidate is deleted. One case cannot be taken back: when the new
     * file opened with the key in memory (unlocked, same master password) the engine
     * has already replaced the offline copy and the vault shows the new file. Then
     * the offline copy is deleted too and the session is locked with
     * [LockReason.SourceChanged], so the old source never shows the new file; the
     * next sync downloads the old file again.
     *
     * Every step is recorded in the [journal] first. If the process dies in the
     * middle, [recoverInterruptedSwitch] undoes the rest at the next start.
     *
     * When the old copy was set aside, an open vault (or an unlock in progress) is
     * the old file; autofill would keep serving it, so the session locks with
     * [LockReason.SourceChanged] and the next unlock opens the new file.
     *
     * Lock order: [syncMutex], then the engine's remote and file locks, then
     * [stateLock] (innermost, never held across a call). [lock] takes none of the
     * first three.
     */
    internal fun startSwitch(change: SourceSwitch): Deferred<SourceSwitchResult> {
        // Set before this returns, so an unlock that starts right after already sees it.
        synchronized(stateLock) { switching++ }
        return scope.async {
            try {
                syncMutex.withLock { runSwitch(change) }
            } finally {
                synchronized(stateLock) { switching-- }
            }
        }
    }

    internal suspend fun switchSource(change: SourceSwitch): SourceSwitchResult = startSwitch(change).await()

    private suspend fun runSwitch(change: SourceSwitch): SourceSwitchResult {
        var record = SwitchRecord(change.target, change.differs, setAside = false, offlineReplaced = false, passwordTouched = false)
        var journaled = false
        var stageCalled = false
        var candidateAdded = false
        var saved = false
        try {
            try {
                withContext(io) { journal.write(record) }
                journaled = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return SourceSwitchResult.Failed(SyncProblem.LOCAL_STORAGE)
            }
            val handle = object : RemoteHandle, RemoteSource by change.remote {
                override fun close() = Unit
            }
            val result = syncLocked(SyncTrigger.SETUP, report = false) { handle }
            when (result) {
                is SyncResult.Failed -> return SourceSwitchResult.Failed(result.problem)
                SyncResult.NotConfigured -> return SourceSwitchResult.Failed(SyncProblem.REMOTE_ERROR)
                else -> Unit
            }
            candidateAdded = change.differs
            var setAside = false
            if (change.differs) {
                try {
                    setAside = withContext(io) { engine.markSourceChanged() }
                } catch (_: IOException) {
                    return SourceSwitchResult.Failed(SyncProblem.LOCAL_STORAGE)
                }
                // No candidate and an offline copy: the new file replaced it directly (it opened with the key in memory).
                val status = engine.status()
                val replaced = !setAside && result != SyncResult.UpToDate &&
                    status.candidate == CandidateState.NONE && status.hasLocalDatabase
                record = record.copy(setAside = setAside, offlineReplaced = replaced)
                withContext(io) { journalQuietly(record) }
            }
            try {
                record = record.copy(passwordTouched = true)
                withContext(io) { journal.write(record) }
                stageCalled = true
                withContext(io) { change.stage() }
                withContext(io) { change.commit() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return SourceSwitchResult.Failed(SyncProblem.LOCAL_STORAGE)
            }
            saved = true
            if (setAside && state.value !is SessionState.Locked) lock(LockReason.SourceChanged)
            emitReport(SyncTrigger.SETUP, result)
            return SourceSwitchResult.Saved(result)
        } finally {
            withContext(NonCancellable + io) {
                try {
                    if (!saved && journaled) rollbackSwitch(record, change, stageCalled, candidateAdded)
                } finally {
                    try {
                        change.finish()
                    } catch (_: Exception) {
                        // Nothing more to do.
                    }
                    journalQuietly(null)
                }
            }
            publishSync()
        }
    }

    private fun journalQuietly(record: SwitchRecord?) {
        try {
            journal.write(record)
        } catch (_: Exception) {
            // Best effort: the steps that matter were written before.
        }
    }

    /** Takes back a failed change; every step on its own, so one failing step never stops the others. */
    private fun rollbackSwitch(record: SwitchRecord, change: SourceSwitch, stageCalled: Boolean, candidateAdded: Boolean) {
        undoEngineSteps(record, candidateAdded)
        if (record.offlineReplaced && state.value !is SessionState.Locked) lock(LockReason.SourceChanged)
        if (stageCalled) {
            try {
                change.rollback()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private fun undoEngineSteps(record: SwitchRecord, candidateAdded: Boolean) {
        if (record.setAside) {
            try {
                engine.undoSourceChanged()
            } catch (_: Exception) {
                // The copy stays set aside; the next sync downloads the file again.
            }
        }
        if (candidateAdded) {
            try {
                engine.discardCandidate()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        if (record.offlineReplaced) {
            try {
                engine.reset()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    /**
     * At the start of the process: if a source change was running when the process
     * died, finish undoing it (old copy back, foreign candidate deleted, and the
     * stored SMB password cleared when it may belong to the new server, so the user
     * is asked instead of a wrong password being sent). A change that had saved its
     * source already is left as it is. Unlocks wait ([UnlockOutcome.Busy]) until
     * this is done. No-op without a journal record.
     */
    internal suspend fun recoverInterruptedSwitch() {
        if (!recoveryPending) return
        scope.async {
            try {
                syncMutex.withLock {
                    withContext(NonCancellable + io) {
                        val record = try {
                            journal.read()
                        } catch (_: Exception) {
                            null
                        }
                        if (record != null) {
                            val committed = record.target != null && record.target == settings.settings.value.source
                            if (!committed) {
                                undoEngineSteps(record, candidateAdded = record.differs)
                                if (record.passwordTouched) {
                                    try {
                                        clearSmbPassword()
                                    } catch (_: Exception) {
                                        // Best effort.
                                    }
                                }
                            }
                            journalQuietly(null)
                        }
                    }
                }
            } finally {
                synchronized(stateLock) {
                    if (recoveryPending) {
                        recoveryPending = false
                        switching--
                    }
                }
            }
        }.await()
        publishSync()
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
            settings.update { it.copy(fingerprintEnabled = false, fingerprintExpired = false) }
            clearStoredHash()
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
            withContext(io) { fingerprintMutex.withLock { hashStore.store(copy, expiry.dueAt(entry, atEntry, s.passwordInterval)) } }
            settings.update { it.copy(fingerprintExpired = false) }
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
