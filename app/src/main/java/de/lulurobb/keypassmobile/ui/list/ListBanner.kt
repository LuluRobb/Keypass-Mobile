package de.lulurobb.keypassmobile.ui.list

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.ui.common.SourceAction
import de.lulurobb.keypassmobile.ui.common.sinceStamp
import de.lulurobb.keypassmobile.ui.components.Notice
import de.lulurobb.keypassmobile.ui.components.NoticeTone
import de.lulurobb.keypassmobile.ui.components.ScreenPadding
import de.lulurobb.keypassmobile.ui.icons.KpmIcons
import java.time.LocalDateTime

/**
 * What is wrong with the source of the database, for the list banner and the sync status in
 * the settings (UI-09). Only these causes are told apart; "share not reachable" is normal
 * offline use and has no problem value. Each value knows the [action] that fixes it.
 */
enum class SourceProblem(
    val action: SourceAction,
    @StringRes val actionLabel: Int,
    @StringRes val bannerTitle: Int,
) {
    /** File on the phone: moved, renamed or deleted. */
    FILE_NOT_FOUND(SourceAction.RepickFile, R.string.action_repick_file, R.string.banner_file_missing_title),

    /** File on the phone: Android no longer lets the app read it. */
    FILE_NO_ACCESS(SourceAction.RepickFile, R.string.action_repick_file, R.string.banner_file_no_access_title),

    /** SMB: the file is no longer on the share. The fix opens the server form to browse again. */
    SMB_NOT_FOUND(SourceAction.EditSmb, R.string.action_repick_file, R.string.banner_smb_missing_title),

    /** SMB: the server no longer lets the user read the file. */
    SMB_NO_ACCESS(SourceAction.EditSmb, R.string.manage_smb_change, R.string.banner_smb_no_access_title),

    /** SMB: the server rejected the stored login. */
    SMB_LOGIN_FAILED(SourceAction.EditSmb, R.string.manage_smb_change, R.string.banner_smb_login_title),
}

/**
 * Banner above the list. At most one is shown; the ranking is OtherServer, then
 * SourceMissing, then NewVersionRejected (UI-09, see `toListBanner`).
 */
sealed interface ListBanner {
    /** Sync stopped because a different server answered (DS-04). Action: details dialog. */
    data class OtherServer(val host: String) : ListBanner

    /**
     * The source is missing or refuses access (UI-09); the offline copy of [copyFrom] is open
     * (null: no date known, the text drops it). [smbUser] is the SMB user shown in the texts of
     * [SourceProblem.SMB_NO_ACCESS] and [SourceProblem.SMB_LOGIN_FAILED], null for a file.
     * Action: [SourceProblem.action], no dismiss.
     */
    data class SourceMissing(
        val problem: SourceProblem,
        val copyFrom: LocalDateTime?,
        val smbUser: String?,
    ) : ListBanner

    /**
     * The newer file from the share or the phone did not open (EN-06); the offline copy of
     * [copyFrom] is open. [kind] chooses the wording ("Datei vom Share" or "neue Version der Datei").
     */
    data class NewVersionRejected(
        val copyFrom: LocalDateTime?,
        val kind: SourceKind = SourceKind.SMB,
    ) : ListBanner
}

/**
 * The "why" sentence of the sync status in the settings, for example "Die Datei wurde verschoben,
 * umbenannt oder gelöscht." [smbUser] is used by the two SMB user problems.
 */
@Composable
fun SourceProblem.reasonText(smbUser: String?): String = when (this) {
    SourceProblem.FILE_NOT_FOUND -> stringResource(R.string.sync_why_file_missing)
    SourceProblem.FILE_NO_ACCESS -> stringResource(R.string.sync_why_file_no_access)
    SourceProblem.SMB_NOT_FOUND -> stringResource(R.string.sync_why_smb_missing)
    SourceProblem.SMB_NO_ACCESS -> stringResource(R.string.sync_why_smb_no_access, smbUser.orEmpty())
    SourceProblem.SMB_LOGIN_FAILED -> stringResource(R.string.sync_why_smb_login, smbUser.orEmpty())
}

/**
 * Renders [banner] as a notice with its action. [onDetails] is the "Details" button of
 * [ListBanner.OtherServer], [onDismiss] the "OK" of [ListBanner.NewVersionRejected],
 * [onSourceAction] the button of [ListBanner.SourceMissing] with [SourceProblem.action].
 */
@Composable
fun ListBannerNotice(
    banner: ListBanner,
    onDetails: () -> Unit,
    onDismiss: () -> Unit,
    onSourceAction: (SourceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val padded = modifier.padding(horizontal = ScreenPadding).padding(top = 4.dp, bottom = 8.dp)
    val scheme = MaterialTheme.colorScheme
    when (banner) {
        is ListBanner.OtherServer -> Notice(
            tone = NoticeTone.Error,
            icon = KpmIcons.Warning,
            title = stringResource(R.string.banner_other_server_title),
            text = stringResource(R.string.banner_other_server_text, banner.host),
            modifier = padded,
            actions = {
                TextButton(
                    onClick = onDetails,
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.onErrorContainer),
                ) { Text(stringResource(R.string.action_details)) }
            },
        )
        is ListBanner.SourceMissing -> Notice(
            tone = NoticeTone.Warning,
            icon = KpmIcons.Warning,
            title = stringResource(banner.problem.bannerTitle),
            text = sourceMissingText(banner),
            modifier = padded,
            actions = {
                TextButton(
                    onClick = { onSourceAction(banner.problem.action) },
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.onTertiaryContainer),
                ) { Text(stringResource(banner.problem.actionLabel)) }
            },
        )
        is ListBanner.NewVersionRejected -> Notice(
            tone = NoticeTone.Warning,
            icon = KpmIcons.Info,
            title = stringResource(R.string.banner_candidate_title),
            text = newVersionRejectedText(banner),
            modifier = padded,
            actions = {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.onTertiaryContainer),
                ) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
}

/**
 * Body text of [ListBanner.SourceMissing]; the stamp is "von heute, 21:40" or "vom 28.10., 08:12" ([sinceStamp]),
 * without a date the phrase is dropped (no guess).
 */
@Composable
private fun sourceMissingText(banner: ListBanner.SourceMissing): String {
    val stamp = banner.copyFrom?.let { sinceStamp(it) }
    val user = banner.smbUser.orEmpty()
    return when (banner.problem) {
        SourceProblem.FILE_NOT_FOUND,
        SourceProblem.FILE_NO_ACCESS,
        -> if (stamp != null) {
            stringResource(R.string.banner_file_text, stamp)
        } else {
            stringResource(R.string.banner_file_text_undated)
        }
        SourceProblem.SMB_NOT_FOUND -> if (stamp != null) {
            stringResource(R.string.banner_smb_missing_text, stamp)
        } else {
            stringResource(R.string.banner_smb_missing_text_undated)
        }
        SourceProblem.SMB_NO_ACCESS -> if (stamp != null) {
            stringResource(R.string.banner_smb_no_access_text, user, stamp)
        } else {
            stringResource(R.string.banner_smb_no_access_text_undated, user)
        }
        SourceProblem.SMB_LOGIN_FAILED -> if (stamp != null) {
            stringResource(R.string.banner_smb_login_text, user, stamp)
        } else {
            stringResource(R.string.banner_smb_login_text_undated, user)
        }
    }
}

@Composable
private fun newVersionRejectedText(banner: ListBanner.NewVersionRejected): String {
    val stamp = banner.copyFrom?.let { sinceStamp(it) }
    return when {
        banner.kind == SourceKind.FILE && stamp != null -> stringResource(R.string.banner_candidate_text_file, stamp)
        banner.kind == SourceKind.FILE -> stringResource(R.string.banner_candidate_text_file_undated)
        stamp != null -> stringResource(R.string.banner_candidate_text, stamp)
        else -> stringResource(R.string.banner_candidate_text_undated)
    }
}
