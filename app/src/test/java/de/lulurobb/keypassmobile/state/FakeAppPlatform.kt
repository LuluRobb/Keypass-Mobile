package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.settings.PasswordInterval
import de.lulurobb.keypassmobile.platform.biometric.FingerprintAvailability
import de.lulurobb.keypassmobile.platform.session.FingerprintSetupResult
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.PasswordRequiredReason
import de.lulurobb.keypassmobile.platform.session.SyncInfo
import de.lulurobb.keypassmobile.platform.session.SyncReport
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.session.SyncTrigger
import de.lulurobb.keypassmobile.platform.session.UnlockOffer
import de.lulurobb.keypassmobile.platform.session.UnlockOutcome
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.util.UUID

/**
 * [AppPlatform] for state holder tests. State lives in the public Mutable
 * flows (set them to simulate the platform), outcomes come from the queues
 * (the default when a queue is empty), every call is recorded in [calls].
 */
class FakeAppPlatform(
    settings: KpmSettings = KpmSettings(),
    session: SessionView = SessionView.Locked(null),
    sync: SyncInfo = testSyncInfo(),
    override val versionName: String = "0.1.0-test",
) : AppPlatform {

    data class UnlockCall(val password: String, val enableFingerprint: Boolean?)

    val settingsFlow = MutableStateFlow(settings)
    val sessionFlow = MutableStateFlow(session)
    val syncFlow = MutableStateFlow(sync)
    val lastReportFlow = MutableStateFlow<SyncReport?>(null)

    override val settings: StateFlow<KpmSettings> = settingsFlow
    override val session: StateFlow<SessionView> = sessionFlow
    override val sync: StateFlow<SyncInfo> = syncFlow
    override val lastSyncReport: StateFlow<SyncReport?> = lastReportFlow

    // ---- scripts ----

    var offer: UnlockOffer = UnlockOffer.Fingerprint
    var fingerprintUsable = false

    /** Outcomes of unlockWithPassword(), in call order; WrongPassword when empty. */
    val unlockOutcomes = ArrayDeque<UnlockOutcome>()

    /** Outcomes of the prompter(), in call order; a cancelled prompt when empty. */
    val promptOutcomes = ArrayDeque<UnlockOutcome>()

    /** Session set when an unlock outcome is Unlocked; null leaves the session as it is. */
    var sessionOnUnlock: SessionView.Unlocked? = null

    /** Set to suspend unlockWithPassword() until the test completes it (busy state). */
    var unlockGate: CompletableDeferred<Unit>? = null

    val syncResults = ArrayDeque<SyncResult>()
    val trustResults = ArrayDeque<SyncResult>()

    /** Set to suspend syncNow() and trustNewServer() until the test completes it. */
    var syncGate: CompletableDeferred<Unit>? = null

    var fingerprintSetupResult = FingerprintSetupResult.STORED

    /** checkIdle() locks (reason Idle with the current timeout) and returns true. */
    var idleExpired = false

    var dueAt: Instant? = null
    var availability = FingerprintAvailability.AVAILABLE
    var autofillSelected = false
    var notificationsAllowed = false

    /** Passwords by entry for revealPassword(). */
    val passwords = mutableMapOf<UUID, String>()

    /** The arrays revealPassword() handed out, to check that the callers wipe them. */
    val revealed = mutableListOf<CharArray>()
    var copyResult = true

    /** Setup objects handed out by startSourceSetup(); the last one is the current one. */
    val setups = mutableListOf<FakeSourceSetup>()
    var nextSetup: () -> FakeSourceSetup = { FakeSourceSetup() }

    /** File setup objects handed out by startFileSetup(); the last one is the current one. */
    val fileSetups = mutableListOf<FakeFileSetup>()
    var nextFileSetup: () -> FakeFileSetup = { FakeFileSetup() }

    // ---- records ----

    /** Names of the calls with their arguments, e.g. "lock", "copyPassword:<id>", "setIdleTimeout:MINUTES_1". */
    val calls = mutableListOf<String>()
    val unlockCalls = mutableListOf<UnlockCall>()
    private var reportId = 0L

    /** Publishes a sync report as the platform does, with the id growing by one per run. */
    fun emitReport(trigger: SyncTrigger, result: SyncResult) {
        lastReportFlow.value = SyncReport(++reportId, trigger, result)
    }

    /** Fingerprint prompter that answers from [promptOutcomes]. */
    fun prompter(): FingerprintPrompter = FingerprintPrompter {
        calls += "prompt"
        promptOutcomes.removeFirstOrNull() ?: UnlockOutcome.PasswordRequired(PasswordRequiredReason.CANCELLED)
    }

    // ---- AppPlatform ----

    override suspend fun unlockOffer(): UnlockOffer {
        calls += "unlockOffer"
        return offer
    }

    override fun isFingerprintUsable(): Boolean = fingerprintUsable

    override suspend fun unlockWithPassword(password: CharArray, enableFingerprint: Boolean?): UnlockOutcome {
        calls += "unlockWithPassword"
        unlockCalls += UnlockCall(String(password), enableFingerprint)
        // The platform wipes the password.
        password.fill('\u0000')
        unlockGate?.await()
        val outcome = unlockOutcomes.removeFirstOrNull() ?: UnlockOutcome.WrongPassword
        if (outcome is UnlockOutcome.Unlocked) sessionOnUnlock?.let { sessionFlow.value = it }
        return outcome
    }

    override fun lock() {
        calls += "lock"
        sessionFlow.value = SessionView.Locked(LockReason.User)
    }

    override fun touch() {
        calls += "touch"
    }

    override fun checkIdle(): Boolean {
        calls += "checkIdle"
        if (!idleExpired) return false
        sessionFlow.value = SessionView.Locked(LockReason.Idle(settingsFlow.value.idleTimeout))
        return true
    }

    override fun refreshNotification() {
        calls += "refreshNotification"
    }

    override fun revealPassword(id: UUID): CharArray? {
        calls += "revealPassword:$id"
        return passwords[id]?.toCharArray()?.also { revealed += it }
    }

    override fun copyUsername(id: UUID): Boolean {
        calls += "copyUsername:$id"
        return copyResult
    }

    override fun copyPassword(id: UUID): Boolean {
        calls += "copyPassword:$id"
        return copyResult
    }

    override suspend fun syncNow(): SyncResult {
        calls += "syncNow"
        syncGate?.await()
        return syncResults.removeFirstOrNull() ?: SyncResult.UpToDate
    }

    override suspend fun trustNewServer(): SyncResult {
        calls += "trustNewServer"
        syncGate?.await()
        return trustResults.removeFirstOrNull() ?: SyncResult.Updated
    }

    override suspend fun setFingerprintEnabled(on: Boolean): FingerprintSetupResult {
        calls += "setFingerprintEnabled:$on"
        settingsFlow.value = settingsFlow.value.copy(fingerprintEnabled = on)
        return if (on) fingerprintSetupResult else FingerprintSetupResult.DISABLED
    }

    override fun setPasswordInterval(v: PasswordInterval) {
        calls += "setPasswordInterval:$v"
        settingsFlow.value = settingsFlow.value.copy(passwordInterval = v)
    }

    override fun setIdleTimeout(v: IdleTimeout) {
        calls += "setIdleTimeout:$v"
        settingsFlow.value = settingsFlow.value.copy(idleTimeout = v)
    }

    override fun passwordDueAt(): Instant? = dueAt

    override fun completeSetup() {
        calls += "completeSetup"
        settingsFlow.value = settingsFlow.value.copy(setupCompleted = true)
    }

    override fun fingerprintAvailability(): FingerprintAvailability = availability

    override fun isAutofillServiceSelected(): Boolean = autofillSelected

    override fun areNotificationsAllowed(): Boolean = notificationsAllowed

    override fun startSourceSetup(): SourceSetupPort {
        calls += "startSourceSetup"
        return nextSetup().also { setups += it }
    }

    override fun startFileSetup(): FakeFileSetup {
        calls += "startFileSetup"
        return nextFileSetup().also { fileSetups += it }
    }
}
