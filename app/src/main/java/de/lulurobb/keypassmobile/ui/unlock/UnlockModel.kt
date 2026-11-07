package de.lulurobb.keypassmobile.ui.unlock

import de.lulurobb.keypassmobile.model.FillTarget
import de.lulurobb.keypassmobile.platform.biometric.FingerprintAvailability
import de.lulurobb.keypassmobile.platform.session.PasswordRequiredReason
import de.lulurobb.keypassmobile.platform.session.SyncInfo
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.session.UnlockOffer
import de.lulurobb.keypassmobile.platform.session.UnlockOutcome
import de.lulurobb.keypassmobile.platform.settings.FileSource
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.SmbSource
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.AppPlatform
import de.lulurobb.keypassmobile.state.FingerprintPrompter
import de.lulurobb.keypassmobile.state.SessionView
import de.lulurobb.keypassmobile.state.TimeSource
import de.lulurobb.keypassmobile.state.databaseLoadedMessage
import de.lulurobb.keypassmobile.state.sourceKindOf
import de.lulurobb.keypassmobile.state.syncResultMessage
import de.lulurobb.keypassmobile.state.unlockMessage
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.components.SecretInput
import de.lulurobb.keypassmobile.ui.components.SetupStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder of the unlock screen (EN-01 to EN-06), also step 3 of the setup
 * ([firstRun]) and the unlock of the autofill picker ([fillTarget]).
 *
 * Navigation never follows the outcomes: the screen is left when [AppPlatform.session]
 * changes, whoever unlocked. The owner calls [reset] on every transition into Locked, with the
 * lock message, which the screen shows as a notice until the next successful unlock ([lockNotice]
 * builds the first one).
 */
class UnlockModel(
    private val platform: AppPlatform,
    private val scope: CoroutineScope,
    private val time: TimeSource,
    private val messages: AppMessages,
    private val fillTarget: FillTarget?,
    private val firstRun: Boolean,
    lockNotice: UiMessage? = null,
) {

    /** What the model keeps itself; [state] adds settings and session. */
    private data class Inner(
        val mode: UnlockMode,
        val fingerprintUsable: Boolean,
        val lockNotice: UiMessage? = null,
        val reason: PasswordReason? = null,
        val error: PasswordError? = null,
        val problem: UnlockProblem? = null,
        /** Own unlock calls running (password or fingerprint). */
        val running: Int = 0,
        /** The user's choice for the switch in the first run; null follows [fingerprintAvailable]. */
        val fingerprintWantedByUser: Boolean? = null,
        val fingerprintAvailable: Boolean = false,
    )

    private val inner = MutableStateFlow(
        Inner(
            // Guess until the offer arrives, so the screen does not flash the wrong mode.
            mode = if (!firstRun && platform.settings.value.fingerprintEnabled) UnlockMode.Fingerprint else UnlockMode.Password,
            fingerprintUsable = !firstRun && platform.settings.value.fingerprintEnabled,
            fingerprintAvailable = firstRun && fingerprintAvailableNow(),
        ),
    )

    val state: StateFlow<UnlockState> = combine(inner, platform.settings, platform.session, platform.sync) { inner, settings, session, sync ->
        build(inner, settings, session, sync)
    }.stateIn(scope, SharingStarted.Eagerly, build(inner.value, platform.settings.value, platform.session.value, platform.sync.value))

    private val mutablePromptToken = MutableStateFlow(0)

    /**
     * Grows when the screen should open the fingerprint prompt by itself (EN-02): the offer
     * is Fingerprint. 0 means no offer yet; the route does not prompt then.
     */
    val promptToken: StateFlow<Int> = mutablePromptToken.asStateFlow()

    /**
     * True when the last successful unlock stored the fingerprint (the setup flow
     * shows "Eingerichtet, mit Fingerabdruck"). Set before [AppMessages] gets its message;
     * the session state can change a moment earlier, so read it from an effect that
     * runs after the unlock, not at the instant the session flips.
     */
    @Volatile
    var lastFingerprintStored: Boolean = false
        private set

    private var resetJob: Job? = null
    private var syncing = false

    init {
        reset(lockNotice)
    }

    /**
     * Back to the start: asks the platform what to offer, clears the field error and the
     * notices. Called on construction and by the owner on every transition into Locked, with
     * the message of that lock ([lockNotice]; null when there is nothing to say).
     */
    fun reset(lockNotice: UiMessage? = null) {
        resetJob?.cancel()
        inner.update { it.copy(error = null, problem = null, reason = null, lockNotice = lockNotice) }
        if (firstRun) {
            // Master password only; the switch needs the current availability.
            val available = fingerprintAvailableNow()
            inner.update { it.copy(mode = UnlockMode.Password, fingerprintUsable = false, fingerprintAvailable = available) }
            return
        }
        resetJob = scope.launch {
            when (val offer = platform.unlockOffer()) {
                UnlockOffer.Fingerprint -> {
                    inner.update { it.copy(mode = UnlockMode.Fingerprint, fingerprintUsable = true) }
                    mutablePromptToken.update { it + 1 }
                }
                is UnlockOffer.Password -> showPassword(offer.reason)
            }
        }
    }

    /**
     * The user may have changed the fingerprints in the system settings (call on resume). Setup step 3:
     * the switch and the text follow the current availability. Normal unlock: "Doch mit Fingerabdruck"
     * goes away when the fingerprint no longer works.
     */
    fun refreshFingerprint() {
        if (firstRun) {
            val available = fingerprintAvailableNow()
            inner.update { it.copy(fingerprintAvailable = available) }
            return
        }
        val usable = platform.isFingerprintUsable()
        inner.update {
            if (it.mode == UnlockMode.Password && it.fingerprintUsable && !usable) it.copy(fingerprintUsable = false) else it
        }
    }

    fun onFingerprintWantedChange(wanted: Boolean) {
        inner.update { it.copy(fingerprintWantedByUser = wanted) }
    }

    /** "Master-Passwort eingeben" (also after a cancelled prompt): password mode without notice. */
    fun onUsePassword() {
        inner.update { it.copy(mode = UnlockMode.Password, reason = null, fingerprintUsable = true) }
    }

    fun onPasswordEdited() {
        inner.update { if (it.error == null) it else it.copy(error = null) }
    }

    /** "Entsperren" with the typed password. The platform wipes the copy; the field keeps its text until the unlock worked. */
    fun onSubmit(input: SecretInput) = submit(input.isEmpty, input::toCharArray, input::clear)

    /** [onSubmit] without the Android field, so JVM tests can drive it. [read] returns a fresh copy. */
    internal fun submit(isEmpty: Boolean, read: () -> CharArray, clearInput: () -> Unit) {
        if (isBusy()) return
        if (isEmpty) {
            inner.update { it.copy(error = PasswordError.Empty) }
            return
        }
        val chars = read()
        val current = inner.value
        val enableFingerprint = if (firstRun) {
            (current.fingerprintWantedByUser ?: current.fingerprintAvailable) && current.fingerprintAvailable
        } else {
            null
        }
        launchUnlock(clearInput) { platform.unlockWithPassword(chars, enableFingerprint) }
    }

    /** Opens the system prompt through [prompter] and unlocks with its result. */
    fun onFingerprint(prompter: FingerprintPrompter) {
        if (isBusy()) return
        launchUnlock(null) { prompter.prompt() }
    }

    /** "Jetzt synchronisieren" of the problem notice. */
    fun onSyncNow() {
        if (syncing) return
        syncing = true
        val kind = sourceKindOf(platform.settings.value)
        messages.post(UiMessage.SyncStarted)
        scope.launch {
            try {
                val result = platform.syncNow()
                if (result == SyncResult.CandidateReady || result == SyncResult.UpToDate) {
                    inner.update { it.copy(problem = null) }
                    messages.post(databaseLoadedMessage(kind))
                } else {
                    syncResultMessage(result, kind)?.let(messages::post)
                }
            } finally {
                syncing = false
            }
        }
    }

    private fun launchUnlock(clearInput: (() -> Unit)?, call: suspend () -> UnlockOutcome) {
        inner.update { it.copy(running = it.running + 1) }
        scope.launch {
            try {
                handle(call(), clearInput)
            } finally {
                inner.update { it.copy(running = it.running - 1) }
            }
        }
    }

    private fun handle(outcome: UnlockOutcome, clearInput: (() -> Unit)?) {
        when (outcome) {
            is UnlockOutcome.Unlocked -> {
                clearInput?.invoke()
                inner.update { it.copy(lockNotice = null) }
                lastFingerprintStored = outcome.fingerprintRenewedForDays != null
                val message = unlockMessage(outcome, sourceKindOf(platform.settings.value))
                // The setup posts SetupDone instead of "Fingerabdruck ist wieder aktiv".
                if (message != null && !(firstRun && message is UiMessage.FingerprintRenewed)) messages.post(message)
            }
            UnlockOutcome.WrongPassword -> inner.update { it.copy(error = PasswordError.Wrong) }
            is UnlockOutcome.PasswordRequired -> showPassword(outcome.reason)
            UnlockOutcome.NoDatabase -> inner.update { it.copy(problem = UnlockProblem.NoDatabase) }
            UnlockOutcome.Corrupt -> inner.update { it.copy(problem = UnlockProblem.Corrupt) }
            // Locked again right after opening or another unlock runs: the session decides.
            UnlockOutcome.Busy,
            UnlockOutcome.Aborted,
            -> Unit
        }
    }

    /** Password mode with the notice and the "Doch mit Fingerabdruck" offer that [reason] stands for. */
    private fun showPassword(reason: PasswordRequiredReason) {
        val notice: PasswordReason?
        val usable: Boolean
        when (reason) {
            PasswordRequiredReason.NOT_SET_UP -> {
                notice = null
                usable = false
            }
            PasswordRequiredReason.DUE -> {
                notice = PasswordReason.Due(platform.settings.value.passwordInterval.days)
                usable = false
            }
            PasswordRequiredReason.FINGERPRINT_INVALIDATED -> {
                notice = PasswordReason.FingerprintInvalidated
                usable = false
            }
            PasswordRequiredReason.STORED_VALUE_REJECTED -> {
                notice = PasswordReason.StoredKeyRejected
                usable = false
            }
            PasswordRequiredReason.CANCELLED,
            PasswordRequiredReason.FINGERPRINT_FAILED,
            -> {
                notice = null
                usable = platform.isFingerprintUsable()
            }
        }
        if (reason == PasswordRequiredReason.FINGERPRINT_FAILED) messages.post(UiMessage.FingerprintFailed)
        inner.update { it.copy(mode = UnlockMode.Password, reason = notice, fingerprintUsable = usable, error = null) }
    }

    /** An own unlock runs or the other activity is decrypting. Read from the sources, [state] may lag behind. */
    private fun isBusy(): Boolean = inner.value.running > 0 || platform.session.value is SessionView.Unlocking

    private fun fingerprintAvailableNow(): Boolean =
        platform.fingerprintAvailability() == FingerprintAvailability.AVAILABLE

    private fun build(inner: Inner, settings: KpmSettings, session: SessionView, sync: SyncInfo): UnlockState =
        UnlockState(
            fileName = settings.source?.fileName.orEmpty(),
            sourceKind = sourceKindOf(settings),
            place = placeOf(settings.source),
            modified = sync.status.localModified?.let(time::local),
            mode = inner.mode,
            fingerprintUsable = inner.fingerprintUsable,
            passwordReason = inner.reason,
            passwordError = inner.error,
            busy = inner.running > 0 || session is SessionView.Unlocking,
            fillTarget = fillTarget,
            firstRun = if (firstRun) {
                FirstRunUnlock(
                    step = SetupStep(3),
                    passwordIntervalDays = settings.passwordInterval.days,
                    fingerprintWanted = inner.fingerprintWantedByUser ?: inner.fingerprintAvailable,
                    fingerprintAvailable = inner.fingerprintAvailable,
                )
            } else {
                null
            },
            problem = inner.problem,
            lockNotice = inner.lockNotice,
        )
}

/**
 * Where the database lies, for the line under the name (EN-07). SMB: the UNC path of the folder with the
 * host as the user typed it; file: the place found at the pick (null when the provider gave none).
 */
internal fun placeOf(source: SourceConfig?): String? = when (source) {
    is SmbSource -> "\\\\" + (listOf(source.host, source.share) + source.filePath.dropLast(1)).joinToString("\\")
    is FileSource -> source.place
    null -> null
}
