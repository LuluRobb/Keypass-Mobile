package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.sync.OpenedVersion
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.session.ServerChange
import de.lulurobb.keypassmobile.platform.session.SyncInfo
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.SmbSource
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.platform.setup.OlderVersion
import de.lulurobb.keypassmobile.ui.components.OfflineReason
import de.lulurobb.keypassmobile.ui.components.SyncState
import de.lulurobb.keypassmobile.ui.components.SyncStatus
import de.lulurobb.keypassmobile.ui.dialogs.OlderVersionInfo
import de.lulurobb.keypassmobile.ui.dialogs.OtherServerInfo
import de.lulurobb.keypassmobile.ui.list.ListBanner
import de.lulurobb.keypassmobile.ui.list.SourceProblem
import java.time.LocalDateTime

/* Mapping of the platform's sync information to the types of the sync screens. */

/** Type of the configured source; SMB while there is none (the first run has no source yet). */
fun sourceKindOf(settings: KpmSettings): SourceKind = settings.source?.kind ?: SourceKind.SMB

/**
 * Sync state for the list chip and the settings. "Aktuell" only when the unlock confirmed the
 * version of the share or file and no problem has been recorded since (see [hasSyncProblem]); every
 * other case shows the offline copy, with the reason derived from [info], [unlocked] and [kind]
 * and the date of the last successful sync. [kind] is the type of the configured source
 * ([sourceKindOf]); it is kept in [SyncState.kind] for the wording.
 */
fun toSyncState(info: SyncInfo, unlocked: SessionView.Unlocked?, time: TimeSource, kind: SourceKind): SyncState {
    val stamp = copyStamp(info, time)
    val status = if (info.status.openedVersion == OpenedVersion.SHARE && !hasSyncProblem(info)) {
        SyncStatus.Current(stamp)
    } else {
        SyncStatus.OfflineCopy(stamp, offlineReason(info, unlocked, kind))
    }
    return SyncState(status, syncing = info.syncing, kind = kind)
}

/**
 * Banner above the list, at most one (UI-09). Ranking: a pending server question, then a missing
 * or refused source ([sourceProblemOf]), then the rejected new version unless the user
 * [dismissed] it. [source] is the configured source; it gives the type and the SMB user shown in
 * the texts. A source that is only unreachable gets no banner (normal offline use).
 */
fun toListBanner(
    info: SyncInfo,
    unlocked: SessionView.Unlocked,
    dismissed: Boolean,
    time: TimeSource,
    source: SourceConfig?,
): ListBanner? {
    val kind = source?.kind ?: SourceKind.SMB
    info.serverChange?.let { return ListBanner.OtherServer(it.host) }
    sourceProblemOf(info, kind)?.let {
        return ListBanner.SourceMissing(it, copyStamp(info, time), (source as? SmbSource)?.user)
    }
    if (unlocked.newVersionRejected && !dismissed) return ListBanner.NewVersionRejected(copyStamp(info, time), kind)
    return null
}

/**
 * What is wrong with the source, for the list banner and the fix button of the sync status in the
 * settings (UI-09). Null when nothing needs fixing: no problem, only unreachable or another
 * technical problem, or a pending server question (that has its own dialog).
 *
 * File: NOT_FOUND and ACCESS_DENIED. SMB: NOT_FOUND, ACCESS_DENIED and AUTH_FAILED. The other
 * problems (UNREACHABLE, SERVER_CHANGED, REMOTE_ERROR and so on) give null for both types.
 */
fun sourceProblemOf(info: SyncInfo, kind: SourceKind): SourceProblem? {
    if (info.serverChange != null) return null
    val problem = info.status.lastProblem
    return when (kind) {
        SourceKind.FILE -> when (problem) {
            SyncProblem.NOT_FOUND -> SourceProblem.FILE_NOT_FOUND
            SyncProblem.ACCESS_DENIED -> SourceProblem.FILE_NO_ACCESS
            else -> null
        }
        SourceKind.SMB -> when (problem) {
            SyncProblem.NOT_FOUND -> SourceProblem.SMB_NOT_FOUND
            SyncProblem.ACCESS_DENIED -> SourceProblem.SMB_NO_ACCESS
            SyncProblem.AUTH_FAILED -> SourceProblem.SMB_LOGIN_FAILED
            else -> null
        }
    }
}

/** Content of the "Ältere Version" dialog: the two instants as local date and time (DS-14). */
fun toOlderVersionInfo(older: OlderVersion, time: TimeSource): OlderVersionInfo = OlderVersionInfo(
    fileName = older.fileName,
    fileModified = time.local(older.fileModified),
    openModified = time.local(older.openModified),
)

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
 * True when the last sync did not succeed: a problem is recorded (a successful sync clears it)
 * or the server question is pending (the sync is stopped until it is answered).
 */
internal fun hasSyncProblem(info: SyncInfo): Boolean =
    info.status.lastProblem != null || info.serverChange != null

/**
 * Why the offline copy is open. A pending server question wins over everything. A missing or
 * refused source (see [sourceProblemOf]) wins over a rejected new version, as the list banner
 * ranks them (UI-09). A rejected new version wins over a plain problem.
 *
 * SMB: other server, login failed, file not found, no access, rejected version, else "share not
 * reachable". File: not found, no access, rejected version, else [OfflineReason.LocalFileProblem]
 * (read error, damaged file, local storage; UNREACHABLE, AUTH_FAILED and SERVER_CHANGED never
 * occur for a file).
 *
 * Null while a sync runs and no problem is recorded: the copy may be about to be replaced, a
 * reason would be a guess.
 */
internal fun offlineReason(info: SyncInfo, unlocked: SessionView.Unlocked?, kind: SourceKind): OfflineReason? {
    val problem = info.status.lastProblem
    val rejected = unlocked?.newVersionRejected == true || problem == SyncProblem.NEW_VERSION_REJECTED
    return when (kind) {
        SourceKind.SMB -> when {
            info.serverChange != null || problem == SyncProblem.SERVER_CHANGED -> OfflineReason.OtherServer
            problem == SyncProblem.AUTH_FAILED -> OfflineReason.LoginFailed
            problem == SyncProblem.NOT_FOUND -> OfflineReason.FileNotFound
            problem == SyncProblem.ACCESS_DENIED -> OfflineReason.NoAccess
            rejected -> OfflineReason.NewVersionRejected
            info.syncing && problem == null -> null
            else -> OfflineReason.ShareUnreachable
        }
        SourceKind.FILE -> when {
            problem == SyncProblem.NOT_FOUND -> OfflineReason.LocalFileNotFound
            problem == SyncProblem.ACCESS_DENIED -> OfflineReason.LocalFileNoAccess
            rejected -> OfflineReason.NewVersionRejected
            info.syncing && problem == null -> null
            else -> OfflineReason.LocalFileProblem
        }
    }
}

/** Date of the version on the device: the last confirmed sync in the system zone, null when there was none (no guess). */
private fun copyStamp(info: SyncInfo, time: TimeSource): LocalDateTime? =
    info.status.lastSuccessfulSync?.let(time::local)
