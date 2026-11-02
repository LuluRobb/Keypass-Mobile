package de.lulurobb.keypassmobile.state

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import de.lulurobb.keypassmobile.core.settings.ClipboardTimeout
import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.settings.PasswordInterval
import de.lulurobb.keypassmobile.platform.KpmPlatform
import de.lulurobb.keypassmobile.platform.biometric.FingerprintAvailability
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardStatus
import de.lulurobb.keypassmobile.platform.session.FingerprintSetupResult
import de.lulurobb.keypassmobile.platform.session.PasswordRequiredReason
import de.lulurobb.keypassmobile.platform.session.SessionState
import de.lulurobb.keypassmobile.platform.session.SyncInfo
import de.lulurobb.keypassmobile.platform.session.SyncReport
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.session.UnlockOffer
import de.lulurobb.keypassmobile.platform.session.UnlockOutcome
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.setup.ChooseResult
import de.lulurobb.keypassmobile.platform.setup.ConnectResult
import de.lulurobb.keypassmobile.platform.setup.FolderResult
import de.lulurobb.keypassmobile.platform.setup.ServerAddress
import de.lulurobb.keypassmobile.platform.setup.SourceSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.lang.ref.WeakReference
import java.time.Instant
import java.util.UUID

/**
 * [AppPlatform] over the real [KpmPlatform]. [scope] drives the mapping of
 * the session state to [SessionView] (the activity's ViewModel scope, so it ends
 * with the activity). Create it once per activity-scoped AppViewModel.
 */
class KpmPlatformAdapter(
    context: Context,
    private val platform: KpmPlatform,
    scope: CoroutineScope,
) : AppPlatform {

    private val app: Context = context.applicationContext
    private val views = SessionViews()

    override val settings: StateFlow<KpmSettings> = platform.settings

    override val session: StateFlow<SessionView> =
        platform.session
            .map(views::of)
            .stateIn(scope, SharingStarted.Eagerly, views.of(platform.session.value))

    override val sync: StateFlow<SyncInfo> = platform.sync

    override val clipboard: StateFlow<ClipboardStatus> = platform.clipboard

    override val lastSyncReport: StateFlow<SyncReport?> = platform.lastSyncReport

    override val versionName: String by lazy {
        try {
            app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0)).versionName.orEmpty()
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }
    }

    override suspend fun unlockOffer(): UnlockOffer = platform.unlockOffer()

    override fun isFingerprintUsable(): Boolean = platform.isFingerprintUsable()

    override suspend fun unlockWithPassword(password: CharArray, enableFingerprint: Boolean?): UnlockOutcome =
        platform.unlockWithPassword(password, enableFingerprint)

    override fun lock() = platform.lock()

    override fun touch() = platform.touch()

    override fun checkIdle(): Boolean = platform.checkIdle()

    override fun refreshNotification() = platform.refreshNotification()

    override fun revealPassword(id: UUID): CharArray? = platform.revealPassword(id)

    override fun copyUsername(id: UUID): Boolean = platform.copyUsername(id)

    override fun copyPassword(id: UUID): Boolean = platform.copyPassword(id)

    override suspend fun syncNow(): SyncResult = platform.syncNow()

    override suspend fun trustNewServer(): SyncResult = platform.trustNewServer()

    override suspend fun setFingerprintEnabled(on: Boolean): FingerprintSetupResult = platform.setFingerprintEnabled(on)

    override fun setPasswordInterval(v: PasswordInterval) = platform.setPasswordInterval(v)

    override fun setIdleTimeout(v: IdleTimeout) = platform.setIdleTimeout(v)

    override fun setClipboardTimeout(v: ClipboardTimeout) = platform.setClipboardTimeout(v)

    override fun passwordDueAt(): Instant? = platform.passwordDueAt()

    override fun completeSetup() = platform.completeSetup()

    override fun fingerprintAvailability(): FingerprintAvailability = platform.system.fingerprintAvailability()

    override fun isAutofillServiceSelected(): Boolean = platform.system.isAutofillServiceSelected()

    override fun areNotificationsAllowed(): Boolean = platform.system.areNotificationsAllowed()

    override fun startSourceSetup(): SourceSetupPort = SourceSetupAdapter(platform.startSourceSetup())

    /**
     * Fingerprint unlock over [activity]; call the prompter on the main thread. The activity is held
     * weakly, because a call may run in a ViewModel scope that outlives it; when it is gone the
     * outcome is the one of a cancelled prompt.
     */
    fun prompter(activity: Activity): FingerprintPrompter {
        val ref = WeakReference(activity)
        return FingerprintPrompter {
            val current = ref.get()
            if (current == null) {
                UnlockOutcome.PasswordRequired(PasswordRequiredReason.CANCELLED)
            } else {
                platform.unlockWithFingerprint(current)
            }
        }
    }

    private class SourceSetupAdapter(private val setup: SourceSetup) : SourceSetupPort {
        override suspend fun connect(address: ServerAddress, password: CharArray, acceptServerGuid: UUID?): ConnectResult =
            setup.connect(address, password, acceptServerGuid)

        override fun canUseStoredPassword(address: ServerAddress): Boolean = setup.canUseStoredPassword(address)

        override suspend fun connectWithStoredPassword(address: ServerAddress, acceptServerGuid: UUID?): ConnectResult =
            setup.connectWithStoredPassword(address, acceptServerGuid)

        override suspend fun listFolder(share: String, path: List<String>): FolderResult = setup.listFolder(share, path)

        override suspend fun chooseFile(share: String, filePath: List<String>): ChooseResult = setup.chooseFile(share, filePath)

        override fun close() = setup.close()
    }
}

/** Maps each [SessionState] once, so the same Unlocked state always yields the same [SessionView] instance. */
internal class SessionViews {
    private var source: SessionState? = null
    private var view: SessionView? = null

    @Synchronized
    fun of(state: SessionState): SessionView {
        view?.let { if (state === source) return it }
        val mapped = when (state) {
            is SessionState.Locked -> SessionView.Locked(state.reason)
            SessionState.Unlocking -> SessionView.Unlocking
            is SessionState.Unlocked -> SessionView.Unlocked(state.vault, state.index, state.newVersionRejected, state.version)
        }
        source = state
        view = mapped
        return mapped
    }
}
