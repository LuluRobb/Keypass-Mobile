package de.lulurobb.keypassmobile.ui.settings

import de.lulurobb.keypassmobile.platform.settings.FileSource
import de.lulurobb.keypassmobile.platform.settings.SmbSource
import de.lulurobb.keypassmobile.platform.smb.ServerNames
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.AppPlatform
import de.lulurobb.keypassmobile.state.SessionView
import de.lulurobb.keypassmobile.state.TimeSource
import de.lulurobb.keypassmobile.state.fingerprintSetupMessage
import de.lulurobb.keypassmobile.state.idleTimeoutOf
import de.lulurobb.keypassmobile.state.passwordIntervalOf
import de.lulurobb.keypassmobile.state.seconds
import de.lulurobb.keypassmobile.state.sourceKindOf
import de.lulurobb.keypassmobile.state.sourceProblemOf
import de.lulurobb.keypassmobile.state.syncResultMessage
import de.lulurobb.keypassmobile.state.toSyncState
import de.lulurobb.keypassmobile.state.toUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * State holder of the settings screen. Persisted settings, session and sync
 * state are followed live; the system values (autofill service, notifications,
 * fingerprint hardware) are no flows and cost a system call each, so they are
 * read at construction and by [refreshSystem] only (call it in onResume). The
 * password due date is read with every update.
 *
 * [onNoSource] is called once when there is no source (settings opened before
 * the setup finished); [state] is null until a source exists.
 */
class SettingsModel(
    private val platform: AppPlatform,
    private val scope: CoroutineScope,
    private val time: TimeSource,
    private val messages: AppMessages,
    private val onNoSource: () -> Unit,
) {
    private val mutableState = MutableStateFlow<SettingsState?>(null)

    val state: StateFlow<SettingsState?> = mutableState.asStateFlow()

    private var noSourceReported = false

    private var fingerprintAvailability = platform.fingerprintAvailability().toUi()
    private var autofillSelected = platform.isAutofillServiceSelected()
    private var notificationsAllowed = platform.areNotificationsAllowed()

    init {
        recompute()
        scope.launch {
            combine(platform.settings, platform.session, platform.sync) { _, _, _ -> }.collect { recompute() }
        }
    }

    /** Re-reads autofill, notifications, fingerprint availability and the password due date. */
    fun refreshSystem() {
        fingerprintAvailability = platform.fingerprintAvailability().toUi()
        autofillSelected = platform.isAutofillServiceSelected()
        notificationsAllowed = platform.areNotificationsAllowed()
        recompute()
    }

    fun onFingerprintChange(on: Boolean) {
        scope.launch {
            val result = platform.setFingerprintEnabled(on)
            fingerprintSetupMessage(result)?.let(messages::post)
            recompute()
        }
    }

    /** Unknown values are ignored. */
    fun onPasswordIntervalChange(days: Int) {
        val interval = passwordIntervalOf(days) ?: return
        platform.setPasswordInterval(interval)
        recompute()
    }

    /** Unknown values are ignored. */
    fun onAutoLockChange(seconds: Int) {
        val timeout = idleTimeoutOf(seconds) ?: return
        platform.setIdleTimeout(timeout)
        recompute()
    }

    fun onSyncNow() {
        scope.launch {
            val kind = sourceKindOf(platform.settings.value)
            syncResultMessage(platform.syncNow(), kind)?.let(messages::post)
        }
    }

    private fun recompute() {
        val settings = platform.settings.value
        val source = settings.source
        if (source == null) {
            mutableState.value = null
            if (!noSourceReported) {
                noSourceReported = true
                onNoSource()
            }
            return
        }
        noSourceReported = false
        val unlocked = platform.session.value as? SessionView.Unlocked
        mutableState.value = SettingsState(
            source = when (source) {
                is SmbSource -> SourceEntry.Smb(
                    info = SourceInfo(source.host, source.share, source.filePath, source.user),
                    server = settings.trustedServer?.let {
                        ServerIdentity(
                            name = ServerNames.fromGuid(it.guid) ?: source.host,
                            rememberedOn = time.local(it.since).toLocalDate(),
                            guid = it.guid.toString(),
                        )
                    },
                )
                is FileSource -> SourceEntry.File(source.displayName, source.place)
            },
            sync = toSyncState(platform.sync.value, unlocked, time, source.kind),
            sourceProblem = sourceProblemOf(platform.sync.value, source.kind),
            fingerprintEnabled = settings.fingerprintEnabled,
            fingerprintAvailability = fingerprintAvailability,
            passwordDueOn = platform.passwordDueAt()?.let { time.local(it).toLocalDate() },
            passwordIntervalDays = settings.passwordInterval.days,
            autoLockSeconds = settings.idleTimeout.seconds,
            autofillSelected = autofillSelected,
            notificationsAllowed = notificationsAllowed,
            versionName = platform.versionName,
        )
    }
}
