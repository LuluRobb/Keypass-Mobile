package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.index.EntryIndex
import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.settings.PasswordInterval
import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.platform.biometric.FingerprintAvailability
import de.lulurobb.keypassmobile.platform.session.FingerprintSetupResult
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.SyncInfo
import de.lulurobb.keypassmobile.platform.session.SyncReport
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.session.UnlockOffer
import de.lulurobb.keypassmobile.platform.session.UnlockOutcome
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.setup.ChooseResult
import de.lulurobb.keypassmobile.platform.setup.ConnectResult
import de.lulurobb.keypassmobile.platform.setup.FileSourceSetup
import de.lulurobb.keypassmobile.platform.setup.FolderResult
import de.lulurobb.keypassmobile.platform.setup.OlderVersion
import de.lulurobb.keypassmobile.platform.setup.ServerAddress
import de.lulurobb.keypassmobile.ui.about.AboutInfo
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.time.Instant
import java.util.UUID

/**
 * What the state holders of :app need from :platform. One adapter
 * ([KpmPlatformAdapter]) wraps KpmPlatform; tests use a fake. The interface
 * exists because [SessionView.Unlocked] and SourceSetup have internal
 * constructors in :platform, so KpmPlatform cannot be built or faked in JVM tests.
 *
 * Same semantics as the KpmPlatform functions of the same name.
 */
interface AppPlatform {
    /** Persisted settings, source and server identity. */
    val settings: StateFlow<KpmSettings>

    /** Locked / Unlocking / Unlocked with vault and index. */
    val session: StateFlow<SessionView>

    /** Sync status, running flag and a pending server question. */
    val sync: StateFlow<SyncInfo>

    /**
     * The newest finished sync run (after unlock, requested, scheduled, setup), null before
     * the first. [SyncReport.id] grows by one per run: remember the last id shown and show
     * each report once, also when collecting late.
     */
    val lastSyncReport: StateFlow<SyncReport?>

    /** versionName of the app for the version line of the settings row "Über KeyPass Mobile". */
    val versionName: String

    /** App and device facts for the About screen. The same object every time, so the screen keeps its identity. */
    val aboutInfo: AboutInfo

    /** What the unlock screen offers first. Deletes the stored value when the password is due. */
    suspend fun unlockOffer(): UnlockOffer

    /** The fingerprint can unlock now ("Doch mit Fingerabdruck"). Free of side effects. */
    fun isFingerprintUsable(): Boolean

    /** [password] is wiped. [enableFingerprint] non-null sets the fingerprint switch (setup step 3). */
    suspend fun unlockWithPassword(password: CharArray, enableFingerprint: Boolean? = null): UnlockOutcome

    /** "Sperren" in the app. */
    fun lock()

    /** User activity (idle timer). */
    fun touch()

    /** Locks if the idle time passed. Returns true if it locked. Call in onResume. */
    fun checkIdle(): Boolean

    /** Shows the "entsperrt" notification again when it is missing and notifications are allowed now. Call in onResume. */
    fun refreshNotification()

    /** Password of [id] for autofill; the caller wipes the array. Null when locked or unknown. */
    fun revealPassword(id: UUID): CharArray?

    /** False when locked or unknown entry. */
    fun copyUsername(id: UUID): Boolean

    /** False when locked or unknown entry. */
    fun copyPassword(id: UUID): Boolean

    /** "Jetzt synchronisieren". */
    suspend fun syncNow(): SyncResult

    /** Accepts the new server GUID of SyncInfo.serverChange and syncs. */
    suspend fun trustNewServer(): SyncResult

    suspend fun setFingerprintEnabled(on: Boolean): FingerprintSetupResult

    fun setPasswordInterval(v: PasswordInterval)

    fun setIdleTimeout(v: IdleTimeout)

    /** When the master password is due again; null when the fingerprint is off or never stored. */
    fun passwordDueAt(): Instant?

    /** End of the first-run setup. */
    fun completeSetup()

    fun fingerprintAvailability(): FingerprintAvailability

    fun isAutofillServiceSelected(): Boolean

    fun areNotificationsAllowed(): Boolean

    /** Starts the setup or a source change. Close the returned object when leaving the flow. */
    fun startSourceSetup(): SourceSetupPort

    /** Starts the setup or a source change with a file on the phone. Close the returned object when leaving the flow. */
    fun startFileSetup(): FileSourceSetup
}

/**
 * [de.lulurobb.keypassmobile.platform.session.SessionState] with public
 * constructors, so tests can build every state.
 */
sealed interface SessionView {
    /** [reason] says why it was locked last; null at start and after a failed unlock. */
    data class Locked(val reason: LockReason?) : SessionView

    /** Decrypting: the unlock screen shows the busy state. */
    data object Unlocking : SessionView

    /**
     * The database is open. [vault] and [index] are replaced when a newer version was
     * opened in the background; [version] then increases. Not a data class: identity
     * decides, every opened vault is a new state.
     */
    class Unlocked(
        val vault: Vault,
        val index: EntryIndex,
        /** The share has a newer file that does not open with this key (list banner until a lock). */
        val newVersionRejected: Boolean,
        val version: Int,
    ) : SessionView
}

/** [de.lulurobb.keypassmobile.platform.setup.SourceSetup] as interface, see [AppPlatform]. */
interface SourceSetupPort : Closeable {
    suspend fun connect(address: ServerAddress, password: CharArray, acceptServerGuid: UUID? = null): ConnectResult

    suspend fun listFolder(share: String, path: List<String>): FolderResult

    suspend fun chooseFile(share: String, filePath: List<String>): ChooseResult

    /** The file is older than the open version and is the same database (DS-14); null when no dialog is due. */
    suspend fun olderVersion(share: String, filePath: List<String>): OlderVersion?
}

/**
 * Shows the system fingerprint prompt and unlocks with the result. The adapter makes
 * one per activity ([KpmPlatformAdapter.prompter]), so the state holders need no Activity.
 */
fun interface FingerprintPrompter {
    suspend fun prompt(): UnlockOutcome
}
