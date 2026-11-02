package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.sync.OpenedVersion
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.session.ServerChange
import de.lulurobb.keypassmobile.platform.session.SyncInfo
import de.lulurobb.keypassmobile.ui.components.OfflineReason
import de.lulurobb.keypassmobile.ui.components.SyncState
import de.lulurobb.keypassmobile.ui.components.SyncStatus
import de.lulurobb.keypassmobile.ui.dialogs.OtherServerInfo
import de.lulurobb.keypassmobile.ui.list.ListBanner
import java.time.LocalDateTime

/* Mapping of the platform's sync information to the types of the sync screens. */

/**
 * Sync state for the list chip and the settings. The version of the share is
 * open ("Aktuell") only when the unlock confirmed it; every other case shows the
 * offline copy, with the reason derived from [info] and [unlocked].
 */
fun toSyncState(info: SyncInfo, unlocked: SessionView.Unlocked?, time: TimeSource): SyncState {
    val stamp = copyStamp(info, time)
    val status = if (info.status.openedVersion == OpenedVersion.SHARE) {
        SyncStatus.Current(stamp)
    } else {
        SyncStatus.OfflineCopy(stamp, offlineReason(info, unlocked))
    }
    return SyncState(status, syncing = info.syncing)
}

/**
 * Banner above the list: a pending server question has priority, then the
 * rejected new version unless the user [dismissed] it.
 */
fun toListBanner(info: SyncInfo, unlocked: SessionView.Unlocked, dismissed: Boolean, time: TimeSource): ListBanner? {
    info.serverChange?.let { return ListBanner.OtherServer(it.host) }
    if (unlocked.newVersionRejected && !dismissed) return ListBanner.NewVersionRejected(copyStamp(info, time))
    return null
}

/** Content of the "Anderer Server" dialog; GUIDs as strings, the remembered name falls back to the GUID, then the host. */
fun toOtherServerInfo(change: ServerChange): OtherServerInfo = OtherServerInfo(
    host = change.host,
    rememberedName = rememberedNameOf(change),
    rememberedGuid = change.rememberedGuid?.toString().orEmpty(),
    currentName = change.newName,
    currentGuid = change.newGuid.toString(),
)

/** Name of the server the app remembered: decoded name, else GUID, else the host. */
internal fun rememberedNameOf(change: ServerChange): String =
    change.rememberedName ?: change.rememberedGuid?.toString() ?: change.host

/**
 * Why the offline copy is open. A pending server question and a rejected new version win over a plain problem.
 * Null while a sync runs and no problem is recorded: the copy may be about to be replaced, "Share nicht
 * erreichbar" would be a guess.
 */
internal fun offlineReason(info: SyncInfo, unlocked: SessionView.Unlocked?): OfflineReason? {
    val problem = info.status.lastProblem
    return when {
        info.serverChange != null || problem == SyncProblem.SERVER_CHANGED -> OfflineReason.OtherServer
        unlocked?.newVersionRejected == true || problem == SyncProblem.NEW_VERSION_REJECTED -> OfflineReason.NewVersionRejected
        problem == SyncProblem.AUTH_FAILED -> OfflineReason.LoginFailed
        problem == SyncProblem.NOT_FOUND -> OfflineReason.FileNotFound
        problem == SyncProblem.ACCESS_DENIED -> OfflineReason.NoAccess
        info.syncing && problem == null -> null
        else -> OfflineReason.ShareUnreachable
    }
}

/** Date of the version on the device: the last confirmed sync in the system zone, null when there was none (no guess). */
private fun copyStamp(info: SyncInfo, time: TimeSource): LocalDateTime? =
    info.status.lastSuccessfulSync?.let(time::local)
