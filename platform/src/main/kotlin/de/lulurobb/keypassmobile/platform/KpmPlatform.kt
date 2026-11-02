package de.lulurobb.keypassmobile.platform

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import de.lulurobb.keypassmobile.core.match.Domains
import de.lulurobb.keypassmobile.core.settings.ClipboardTimeout
import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.settings.PasswordExpiry
import de.lulurobb.keypassmobile.core.settings.PasswordInterval
import de.lulurobb.keypassmobile.core.sync.SyncEngine
import de.lulurobb.keypassmobile.kdbx.PasswordKey
import de.lulurobb.keypassmobile.platform.biometric.FingerprintPrompt
import de.lulurobb.keypassmobile.platform.biometric.PromptResult
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardGuard
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardStatus
import de.lulurobb.keypassmobile.platform.clipboard.CopiedValue
import de.lulurobb.keypassmobile.platform.clipboard.FileClipboardMarkers
import de.lulurobb.keypassmobile.platform.clipboard.KeystoreClipboardMac
import de.lulurobb.keypassmobile.platform.clipboard.PENDING_CLEAR_FILE
import de.lulurobb.keypassmobile.platform.clipboard.SystemClipboardAlarms
import de.lulurobb.keypassmobile.platform.kdbx.KdbxVaultOpener
import de.lulurobb.keypassmobile.platform.kdbx.NativeArgon2Engine
import de.lulurobb.keypassmobile.platform.keystore.DecryptPreparation
import de.lulurobb.keypassmobile.platform.keystore.KeystoreHashStore
import de.lulurobb.keypassmobile.platform.keystore.SmbPasswordStore
import de.lulurobb.keypassmobile.platform.service.UnlockedNotification
import de.lulurobb.keypassmobile.platform.service.UnlockedService
import de.lulurobb.keypassmobile.platform.session.FileSwitchJournal
import de.lulurobb.keypassmobile.platform.session.FingerprintSetupResult
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.PasswordRequiredReason
import de.lulurobb.keypassmobile.platform.session.RemoteFactory
import de.lulurobb.keypassmobile.platform.session.Session
import de.lulurobb.keypassmobile.platform.session.SessionEffects
import de.lulurobb.keypassmobile.platform.session.SessionState
import de.lulurobb.keypassmobile.platform.session.SyncInfo
import de.lulurobb.keypassmobile.platform.session.SyncReport
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.session.SyncTrigger
import de.lulurobb.keypassmobile.platform.session.UnlockOffer
import de.lulurobb.keypassmobile.platform.session.UnlockOutcome
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.PrefsSettingsRepository
import de.lulurobb.keypassmobile.platform.settings.TrustedServer
import de.lulurobb.keypassmobile.platform.setup.SourceSetup
import de.lulurobb.keypassmobile.platform.setup.forSetup
import de.lulurobb.keypassmobile.platform.smb.ServerGuidListener
import de.lulurobb.keypassmobile.platform.smb.SmbRemoteSource
import de.lulurobb.keypassmobile.platform.smb.smbPath
import de.lulurobb.keypassmobile.platform.sync.SyncScheduler
import de.lulurobb.keypassmobile.smb.SmbConnector
import de.lulurobb.keypassmobile.smb.SmbServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.CharBuffer
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Facade of the platform layer for :app: settings, unlock and lock, the
 * unlocked vault, clipboard, sync and setup. One instance per process; call
 * [init] from Application.onCreate (services and workers use [get], which
 * initializes lazily as well).
 *
 * Everything is observable as [StateFlow]; suspend functions may be called
 * from the main thread, they move blocking work to background threads and
 * finish their state changes even when the caller is cancelled.
 */
class KpmPlatform private constructor(context: Context) {

    private val app: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val io = Dispatchers.IO
    private val clock: Clock = Clock.systemUTC()
    private val elapsed: () -> Long = SystemClock::elapsedRealtime
    private val expiry = PasswordExpiry(clock)

    private val strongBox = app.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    private val keyDir = File(app.noBackupFilesDir, "keys")
    private val settingsRepository = PrefsSettingsRepository(app)
    private val hashStore = KeystoreHashStore(File(keyDir, "fingerprint-hash.bin"), strongBox)
    private val smbPasswords = SmbPasswordStore(File(keyDir, "smb-password.bin"), strongBox)
    private val connector = SmbConnector()
    private val engine = SyncEngine(File(app.noBackupFilesDir, "sync"), KdbxVaultOpener(NativeArgon2Engine()), clock)
    private val clipboardGuard = ClipboardGuard(
        app, scope, elapsed, clock,
        mac = KeystoreClipboardMac(),
        markers = FileClipboardMarkers(File(keyDir, PENDING_CLEAR_FILE)),
        alarms = SystemClipboardAlarms(app),
    )
    private val promptActive = AtomicBoolean(false)

    private val guidListener = object : ServerGuidListener {
        override fun onConnected(guid: UUID) {
            settingsRepository.update { s ->
                val trusted = s.trustedServer
                when {
                    // First successful sync without a GUID from the setup (DS-04).
                    trusted == null -> s.copy(trustedServer = TrustedServer(guid, clock.instant()), pendingServerGuid = null)
                    // The remembered server answers again: the question is obsolete.
                    trusted.guid == guid && s.pendingServerGuid != null -> s.copy(pendingServerGuid = null)
                    else -> s
                }
            }
        }

        override fun onServerChanged(guid: UUID) {
            settingsRepository.update { it.copy(pendingServerGuid = guid) }
        }
    }

    private val remotes = RemoteFactory {
        val s = settingsRepository.settings.value
        val source = s.source ?: return@RemoteFactory null
        val server = try {
            SmbServer(source.host, source.port, source.domain, source.user)
        } catch (_: IllegalArgumentException) {
            return@RemoteFactory null
        }
        SmbRemoteSource(
            connector = connector,
            server = server,
            share = source.share,
            path = smbPath(source.filePath),
            expectedGuid = s.trustedServer?.guid,
            password = smbPasswords::load,
            listener = guidListener,
        )
    }

    private val effects = object : SessionEffects {
        override fun onUnlocked() {
            // Without the service, lock on swipe-away (SE-01) and the notification button would be lost.
            if (!UnlockedService.start(app)) core.lock(LockReason.ServiceRefused)
        }

        // The clipboard is cleared before the state says Locked (and once more after, which does nothing then).
        override fun onLocking() = clipboardGuard.clearNow()

        override fun onLocked(reason: LockReason) = clipboardGuard.clearNow()
    }

    private val core: Session = Session(
        engine = engine,
        hashStore = hashStore,
        settings = settingsRepository,
        remotes = remotes,
        effects = effects,
        scope = scope,
        io = io,
        clock = clock,
        elapsedMillis = elapsed,
        hasher = PasswordKey::hash,
        preload = Domains::preload,
        journal = FileSwitchJournal(File(keyDir, "source-switch.properties")),
        clearSmbPassword = smbPasswords::clear,
    )

    // ---- observable state ----

    /** Persisted settings (UI-03), source and server identity. */
    val settings: StateFlow<KpmSettings> = settingsRepository.settings

    /** Locked / Unlocking / Unlocked with vault and index. */
    val session: StateFlow<SessionState> = core.state

    /** Sync status, running flag and a pending server question (SY-08, DS-04). */
    val sync: StateFlow<SyncInfo> = core.sync

    /**
     * One event per finished sync run: the automatic check after an unlock
     * ([SyncTrigger.AFTER_UNLOCK], e.g. "Neue Version geöffnet" via
     * [SyncResult.Updated]), manual and scheduled runs and the test download
     * of the setup. Not replayed; collect from a long-lived scope (e.g. the
     * app's ViewModel) so no event is missed. The state itself stays in [sync]
     * and [session].
     */
    val syncReports: SharedFlow<SyncReport> = core.syncReports

    /**
     * The newest sync report, null before the first run. [SyncReport.id] grows
     * with every run: remember the last id shown and show each report once, also
     * when the screen starts collecting only after the run ended.
     */
    val lastSyncReport: StateFlow<SyncReport?> = core.lastSyncReport

    /** Clipboard countdown (AF-09). */
    val clipboard: StateFlow<ClipboardStatus> = clipboardGuard.status

    /** Autofill service, notifications, fingerprint hardware (UI-06). */
    val system = SystemState(app)

    // ---- unlock and lock ----

    /** What the unlock screen offers first. Deletes the stored value when the password is due (EN-05). */
    suspend fun unlockOffer(): UnlockOffer = core.unlockOffer()

    /** The fingerprint can unlock now ("Doch mit Fingerabdruck" in password mode). Free of side effects. */
    fun isFingerprintUsable(): Boolean = core.isFingerprintUsable()

    /**
     * Unlocks with the typed master password (EN-01). [password] is wiped.
     * [enableFingerprint] non-null sets the fingerprint switch (setup step 3).
     */
    suspend fun unlockWithPassword(password: CharArray, enableFingerprint: Boolean? = null): UnlockOutcome =
        core.unlockWithPassword(password, enableFingerprint)

    /**
     * Fingerprint unlock (EN-02, EN-03): shows the system prompt over
     * [activity] (call on the main thread, e.g. from the unlock screen as soon
     * as it shows in fingerprint mode), decrypts the stored hash and opens the
     * database. Any reason to fall back to the password comes back as
     * [UnlockOutcome.PasswordRequired]. A second call while the prompt shows
     * returns [UnlockOutcome.Busy].
     */
    suspend fun unlockWithFingerprint(activity: Activity): UnlockOutcome {
        if (session.value is SessionState.Unlocked) {
            return UnlockOutcome.Unlocked(newVersionOpened = false, candidateRejected = null, fingerprintRenewedForDays = null)
        }
        if (!promptActive.compareAndSet(false, true)) return UnlockOutcome.Busy
        try {
            core.expireFingerprint()
            core.fingerprintBlocker()?.let { return UnlockOutcome.PasswordRequired(it) }
            val ready = when (val prepared = withContext(io) { hashStore.prepareDecrypt() }) {
                is DecryptPreparation.Ready -> prepared
                DecryptPreparation.Missing -> return required(PasswordRequiredReason.NOT_SET_UP)
                DecryptPreparation.Invalidated -> return required(PasswordRequiredReason.FINGERPRINT_INVALIDATED)
                DecryptPreparation.Expired -> {
                    core.clearStoredHash(expired = true)
                    return required(PasswordRequiredReason.DUE)
                }
                DecryptPreparation.Failed -> return required(PasswordRequiredReason.FINGERPRINT_FAILED)
            }
            val cipher = when (val result = withContext(Dispatchers.Main.immediate) { FingerprintPrompt.authenticate(activity, ready.cipher) }) {
                is PromptResult.Authenticated -> result.cipher
                PromptResult.Cancelled -> return required(PasswordRequiredReason.CANCELLED)
                is PromptResult.Error -> return required(PasswordRequiredReason.FINGERPRINT_FAILED)
            }
            val hash = try {
                withContext(io) { cipher.doFinal(ready.ciphertext) }
            } catch (e: Exception) {
                return when (KeystoreHashStore.classify(e)) {
                    DecryptPreparation.Invalidated -> {
                        core.clearStoredHash()
                        required(PasswordRequiredReason.FINGERPRINT_INVALIDATED)
                    }
                    DecryptPreparation.Expired -> {
                        core.clearStoredHash(expired = true)
                        required(PasswordRequiredReason.DUE)
                    }
                    else -> required(PasswordRequiredReason.FINGERPRINT_FAILED)
                }
            }
            if (hash.size != PasswordKey.HASH_LENGTH) {
                hash.fill(0)
                core.clearStoredHash()
                return required(PasswordRequiredReason.NOT_SET_UP)
            }
            return core.unlockWithStoredHash(hash)
        } finally {
            promptActive.set(false)
        }
    }

    private fun required(reason: PasswordRequiredReason) = UnlockOutcome.PasswordRequired(reason)

    /** "Sperren" in the app. */
    fun lock() = core.lock(LockReason.User)

    internal fun lock(reason: LockReason) = core.lock(reason)

    /** User activity (SE-02). Call on every interaction in the app and for autofill requests. */
    fun touch() = core.touch()

    /**
     * Locks if the idle time passed (e.g. in deep sleep). Call in onResume. Returns true if it locked.
     * Also looks at a copied value that is still pending in the clipboard (see [ClipboardStatus.Cleared]).
     */
    fun checkIdle(): Boolean {
        val locked = core.checkIdle()
        scope.launch { clipboardGuard.checkPending() }
        return locked
    }

    /** The backup alarm of the clipboard guard fired (ClipboardClearReceiver). */
    internal fun checkClipboard() = clipboardGuard.checkPending()

    /**
     * Shows "KeyPass Mobile entsperrt" (SE-03) when it is missing although the session is
     * unlocked and notifications are allowed now: the foreground service started while
     * POST_NOTIFICATIONS was denied, and the user allowed it later (system settings). Call in
     * onResume, next to [checkIdle]. Cheap and without effect when locked, when notifications
     * are still blocked or when the notification is shown already.
     */
    fun refreshNotification() {
        if (!isUnlocked() || !system.areNotificationsAllowed() || UnlockedNotification.isShown(app)) return
        // The service posts it again with startForeground; a service that is already running just does that.
        UnlockedService.start(app)
    }

    // ---- the unlocked vault ----

    /** The unlocked state after an idle check, or null when locked. */
    fun unlocked(): SessionState.Unlocked? = core.unlockedOrNull()

    /**
     * Password of [entryId] for autofill (AF-04). The caller wipes the array
     * right after use. Null when locked or the entry no longer exists.
     * Counts as activity.
     */
    fun revealPassword(entryId: UUID): CharArray? {
        val first = core.unlockedOrNull() ?: return null
        core.touch()
        return try {
            first.vault.revealPassword(entryId)
        } catch (_: Exception) {
            // The vault may just have been replaced by a newer version (SY-04).
            val current = core.unlockedOrNull()?.takeIf { it !== first } ?: return null
            try {
                current.vault.revealPassword(entryId)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun isUnlocked(): Boolean = core.state.value is SessionState.Unlocked

    /** "Benutzername kopieren" (UI-02, AF-09). False when locked or unknown entry. */
    fun copyUsername(entryId: UUID): Boolean {
        val unlocked = core.unlockedOrNull() ?: return false
        val entry = unlocked.vault.entries.firstOrNull { it.id == entryId } ?: return false
        core.touch()
        return clipboardGuard.copy(CopiedValue.USERNAME, entry.username, settings.value.clipboardTimeout, ::isUnlocked)
    }

    /** "Passwort kopieren" (UI-02, AF-09). The password is marked sensitive and cleared after the set time. */
    fun copyPassword(entryId: UUID): Boolean {
        val chars = revealPassword(entryId) ?: return false
        return try {
            clipboardGuard.copy(CopiedValue.PASSWORD, CharBuffer.wrap(chars), settings.value.clipboardTimeout, ::isUnlocked)
        } finally {
            chars.fill('\u0000')
        }
    }

    // ---- sync ----

    /** "Jetzt synchronisieren" (SY-07). */
    suspend fun syncNow(): SyncResult = core.syncNow()

    /** Accepts the new server GUID of [SyncInfo.serverChange] (DS-04) and syncs. */
    suspend fun trustNewServer(): SyncResult = core.trustPendingServer()

    /** Periodic background sync (SY-06), called by the worker. */
    internal suspend fun runScheduledSync(): SyncResult = core.syncNow(SyncTrigger.SCHEDULED)

    // ---- settings ----

    /** Fingerprint on/off (UI-03, EN-03). */
    suspend fun setFingerprintEnabled(enabled: Boolean): FingerprintSetupResult = core.setFingerprintEnabled(enabled)

    /** EN-05. A shorter interval applies at once, a longer one from the next password entry. */
    fun setPasswordInterval(interval: PasswordInterval) {
        settingsRepository.update { it.copy(passwordInterval = interval) }
    }

    /** SE-02; applies to the running idle timer within seconds. */
    fun setIdleTimeout(timeout: IdleTimeout) {
        settingsRepository.update { it.copy(idleTimeout = timeout) }
    }

    /** AF-09; applies from the next copy. */
    fun setClipboardTimeout(timeout: ClipboardTimeout) {
        settingsRepository.update { it.copy(clipboardTimeout = timeout) }
    }

    /** When the master password is due again (EN-05); null when the fingerprint is off or never stored. */
    fun passwordDueAt(): Instant? {
        val s = settings.value
        if (!s.fingerprintEnabled) return null
        val entry = s.lastPasswordEntry ?: return null
        val atEntry = s.intervalAtEntry ?: return null
        return expiry.dueAt(entry, atEntry, s.passwordInterval)
    }

    /** End of the first-run setup: remembers it and schedules the background sync (SY-06). */
    fun completeSetup() {
        settingsRepository.update { it.copy(setupCompleted = true) }
        SyncScheduler.schedule(app)
    }

    // ---- setup ----

    /** Starts the setup or a source change (DS-02). Close the returned object when leaving the flow. */
    fun startSourceSetup(): SourceSetup = SourceSetup(connector.forSetup(), settingsRepository, smbPasswords, core, clock, io)

    private fun start() {
        // Finishes undoing a source change that a process death interrupted; unlocks wait for it.
        scope.launch { core.recoverInterruptedSwitch() }
        // A copied value whose countdown died with the previous process is cleared now.
        scope.launch { clipboardGuard.checkPending() }
        UnlockedNotification.ensureChannel(app)
        if (settings.value.setupCompleted) SyncScheduler.schedule(app)
    }

    companion object {
        // Holds only the application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: KpmPlatform? = null

        /** Creates the process-wide instance; idempotent. Call from Application.onCreate. */
        fun init(context: Context): KpmPlatform = instance ?: synchronized(this) {
            instance ?: KpmPlatform(context).also {
                instance = it
                it.start()
            }
        }

        /** The process-wide instance (initialized on first use). */
        fun get(context: Context): KpmPlatform = init(context)
    }
}
