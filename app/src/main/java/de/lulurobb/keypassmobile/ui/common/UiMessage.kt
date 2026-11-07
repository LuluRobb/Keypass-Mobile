package de.lulurobb.keypassmobile.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import kotlinx.coroutines.delay

/**
 * Short feedback shown as a snackbar at the bottom of a screen. Screens take
 * `message: UiMessage?` and `onMessageDismissed`; set the message to show it,
 * the screen calls `onMessageDismissed` when it times out. A message with an
 * action ([hasAction], today only [PickFailed]) shows a button; the screen
 * reports its tap with `onMessageAction`.
 */
sealed interface UiMessage {
    /** A username or password was copied (AF-09). The app never clears the clipboard. */
    data class Copied(val value: CopiedValue) : UiMessage
    data object Locked : UiMessage
    /** Locked because the foreground service was refused (SE-03); unlock again with the app in front. */
    data object LockedNoService : UiMessage
    /** Locked because the source was changed to a file the open key does not open; unlock with its password. */
    data object LockedSourceChanged : UiMessage
    /** "Gesperrt nach 2 Minuten ohne Nutzung." (SE-02), shown on the unlock screen. */
    data class AutoLocked(val idleSeconds: Int) : UiMessage
    data class SetupDone(val fingerprint: Boolean) : UiMessage
    /** After entering the due master password (EN-05). */
    data class FingerprintRenewed(val days: Int) : UiMessage
    data object FingerprintFailed : UiMessage
    /** The fingerprint could not be stored after the password entry (e.g. none enrolled). */
    data object FingerprintStoreFailed : UiMessage
    /** The fingerprint switch is on but becomes active only at the next password entry. */
    data object FingerprintOnNextPassword : UiMessage
    data object SyncNoNewer : UiMessage
    data object SyncUpdated : UiMessage

    /** [SyncUpdated] for a file source: "Neue Version aus der Datei geladen." */
    data object SyncUpdatedFile : UiMessage
    /** Sync while locked: the newer file waits for the next unlock, or a source setup loaded the file. */
    data object DatabaseLoaded : UiMessage

    /** [DatabaseLoaded] for a file source: "Datenbank aus der Datei geladen." */
    data object DatabaseLoadedFile : UiMessage
    data object SyncFailed : UiMessage

    /** Sync of a file source: the file has not changed (SY-03). */
    data object SyncFileUnchanged : UiMessage

    /** Sync of a file source: the file is gone; the offline copy stays open (SY-09). */
    data object SyncFileNotFound : UiMessage

    /** Sync of a file source: Android no longer lets the app read the file; the offline copy stays open (SY-09). */
    data object SyncFileNoAccess : UiMessage
    data object SyncUnreachable : UiMessage
    data object SyncOtherServer : UiMessage
    /** Sync now while locked runs as background job (SY-07). */
    data object SyncStarted : UiMessage
    data object ServerAccepted : UiMessage
    data class ShareDenied(val share: String, val user: String) : UiMessage
    data class ShareNotFound(val share: String) : UiMessage
    data object SourceSaved : UiMessage

    /**
     * The file picked with the system picker was rejected (DS-12). Shown for 10 seconds on the
     * screen the picker was opened from, with the action "Neu wählen" ([MessageHost] `onAction`),
     * which opens the picker again in the folder of the rejected file. [fileName] is the display
     * name of the file, never a path or URI.
     */
    data class PickFailed(val reason: PickFailure, val fileName: String) : UiMessage
}

/** Why a picked file was rejected (DS-12). */
enum class PickFailure {
    /** The file does not start like a KeePass database. */
    NotKdbx,

    /** The file was not found or could not be read. */
    Unreadable,

    /** Android refused the lasting read permission. */
    NoLastingAccess,
}

enum class CopiedValue { Username, Password }

@Composable
fun UiMessage.text(): String = when (this) {
    is UiMessage.Copied -> stringResource(
        if (value == CopiedValue.Username) R.string.msg_copied_username else R.string.msg_copied_password,
    )
    UiMessage.Locked -> stringResource(R.string.msg_locked)
    UiMessage.LockedNoService -> stringResource(R.string.msg_locked_no_service)
    UiMessage.LockedSourceChanged -> stringResource(R.string.msg_locked_source_changed)
    is UiMessage.AutoLocked -> stringResource(R.string.msg_auto_locked, durationText(idleSeconds))
    is UiMessage.SetupDone -> stringResource(
        if (fingerprint) R.string.msg_setup_done_fingerprint else R.string.msg_setup_done,
    )
    is UiMessage.FingerprintRenewed -> stringResource(R.string.msg_fingerprint_renewed, daysText(days))
    UiMessage.FingerprintFailed -> stringResource(R.string.msg_fingerprint_failed)
    UiMessage.FingerprintStoreFailed -> stringResource(R.string.msg_fingerprint_store_failed)
    UiMessage.FingerprintOnNextPassword -> stringResource(R.string.msg_fingerprint_on_next_password)
    UiMessage.SyncNoNewer -> stringResource(R.string.msg_sync_no_newer)
    UiMessage.SyncUpdated -> stringResource(R.string.msg_sync_updated)
    UiMessage.SyncUpdatedFile -> stringResource(R.string.msg_sync_updated_file)
    UiMessage.DatabaseLoaded -> stringResource(R.string.msg_database_loaded)
    UiMessage.DatabaseLoadedFile -> stringResource(R.string.msg_database_loaded_file)
    UiMessage.SyncFailed -> stringResource(R.string.msg_sync_failed)
    UiMessage.SyncFileUnchanged -> stringResource(R.string.msg_sync_file_unchanged)
    UiMessage.SyncFileNotFound -> stringResource(R.string.msg_sync_file_not_found)
    UiMessage.SyncFileNoAccess -> stringResource(R.string.msg_sync_file_no_access)
    UiMessage.SyncUnreachable -> stringResource(R.string.msg_sync_unreachable)
    UiMessage.SyncOtherServer -> stringResource(R.string.msg_sync_other_server)
    UiMessage.SyncStarted -> stringResource(R.string.msg_sync_started)
    UiMessage.ServerAccepted -> stringResource(R.string.msg_server_accepted)
    is UiMessage.ShareDenied -> stringResource(R.string.msg_share_denied, share, user)
    is UiMessage.ShareNotFound -> stringResource(R.string.msg_share_not_found, share)
    UiMessage.SourceSaved -> stringResource(R.string.msg_source_saved)
    is UiMessage.PickFailed -> stringResource(
        when (reason) {
            PickFailure.NotKdbx -> R.string.pick_error_not_kdbx
            PickFailure.Unreadable -> R.string.pick_error_unreadable
            PickFailure.NoLastingAccess -> R.string.pick_error_no_lasting_access
        },
        fileName,
    )
}

/** True for a message with an action button: only [UiMessage.PickFailed] ("Neu wählen"). */
val UiMessage.hasAction: Boolean get() = this is UiMessage.PickFailed

private const val MESSAGE_MILLIS = 4000L
private const val LONG_MESSAGE_MILLIS = 6000L
private const val PICK_FAILED_MILLIS = 10_000L

/** How long the snackbar stays: long texts need more reading time, a picker error has an action to reach. */
internal fun UiMessage.durationMillis(): Long = when (this) {
    is UiMessage.PickFailed -> PICK_FAILED_MILLIS
    is UiMessage.ShareDenied,
    UiMessage.LockedNoService,
    UiMessage.LockedSourceChanged,
    UiMessage.FingerprintStoreFailed,
    UiMessage.SyncFileNotFound,
    UiMessage.SyncFileNoAccess,
    -> LONG_MESSAGE_MILLIS
    else -> MESSAGE_MILLIS
}

/**
 * Snackbar host for [UiMessage]. Put it into the Scaffold's snackbarHost slot.
 * The text updates in place when a message replaces another.
 *
 * [onAction] is called when the action button of a message with an action ([hasAction],
 * "Neu wählen" of [UiMessage.PickFailed]) is tapped. The host dismisses the message first
 * ([onDismissed]) and then calls [onAction], so a message posted by the action stays.
 * Messages without an action show no button.
 */
@Composable
fun MessageHost(
    message: UiMessage?,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    onAction: () -> Unit = {},
) {
    val dismiss by rememberUpdatedState(onDismissed)
    val action by rememberUpdatedState(onAction)
    // Keep the last message while the exit animation runs (plain holder, no state).
    val last = remember { arrayOfNulls<UiMessage>(1) }
    if (message != null) last[0] = message
    val shown = last[0]
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        delay(message.durationMillis())
        dismiss()
    }
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { it / 3 },
        exit = fadeOut(tween(150)),
    ) {
        shown?.let {
            val frame = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)
            if (it.hasAction) {
                ActionSnackbar(
                    text = it.text(),
                    actionLabel = stringResource(R.string.pick_again),
                    onAction = {
                        dismiss()
                        action()
                    },
                    modifier = frame,
                )
            } else {
                Snackbar(frame) { Text(it.text()) }
            }
        }
    }
}

/**
 * Snackbar with an action in the Material 3 look: message left (wraps when long), action right.
 * The stock `Snackbar` with `action` cannot be used full width: `fillMaxWidth` reaches its action
 * slot as a minimum width, the button then sits at the left edge on top of the message.
 */
@Composable
private fun ActionSnackbar(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = SnackbarDefaults.shape,
        color = SnackbarDefaults.color,
        contentColor = SnackbarDefaults.contentColor,
        shadowElevation = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                modifier = Modifier.weight(1f).padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = onAction,
                modifier = Modifier.padding(end = 8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
            ) { Text(actionLabel) }
        }
    }
}
