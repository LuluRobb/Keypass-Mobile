package de.lulurobb.keypassmobile.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import kotlinx.coroutines.delay

/**
 * Short feedback shown as a snackbar at the bottom of a screen. Screens take
 * `message: UiMessage?` and `onMessageDismissed`; set the message to show it,
 * the screen calls `onMessageDismissed` when it times out.
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
    /** Sync while locked: the newer file waits for the next unlock, or a source setup loaded the file. */
    data object DatabaseLoaded : UiMessage
    data object SyncFailed : UiMessage
    data object SyncUnreachable : UiMessage
    data object SyncOtherServer : UiMessage
    /** Sync now while locked runs as background job (SY-07). */
    data object SyncStarted : UiMessage
    data object ServerAccepted : UiMessage
    data class ShareDenied(val share: String, val user: String) : UiMessage
    data class ShareNotFound(val share: String) : UiMessage
    data object SourceSaved : UiMessage
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
    UiMessage.DatabaseLoaded -> stringResource(R.string.msg_database_loaded)
    UiMessage.SyncFailed -> stringResource(R.string.msg_sync_failed)
    UiMessage.SyncUnreachable -> stringResource(R.string.msg_sync_unreachable)
    UiMessage.SyncOtherServer -> stringResource(R.string.msg_sync_other_server)
    UiMessage.SyncStarted -> stringResource(R.string.msg_sync_started)
    UiMessage.ServerAccepted -> stringResource(R.string.msg_server_accepted)
    is UiMessage.ShareDenied -> stringResource(R.string.msg_share_denied, share, user)
    is UiMessage.ShareNotFound -> stringResource(R.string.msg_share_not_found, share)
    UiMessage.SourceSaved -> stringResource(R.string.msg_source_saved)
}

private const val MESSAGE_MILLIS = 4000L
private const val LONG_MESSAGE_MILLIS = 6000L

/** Long texts that need more reading time. */
private fun UiMessage.isLong(): Boolean = when (this) {
    is UiMessage.ShareDenied,
    UiMessage.LockedNoService,
    UiMessage.LockedSourceChanged,
    UiMessage.FingerprintStoreFailed,
    -> true
    else -> false
}

/**
 * Snackbar host for [UiMessage]. Put it into the Scaffold's snackbarHost slot.
 * The text updates in place when a message replaces another.
 */
@Composable
fun MessageHost(message: UiMessage?, onDismissed: () -> Unit, modifier: Modifier = Modifier) {
    val dismiss by rememberUpdatedState(onDismissed)
    // Keep the last message while the exit animation runs (plain holder, no state).
    val last = remember { arrayOfNulls<UiMessage>(1) }
    if (message != null) last[0] = message
    val shown = last[0]
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        delay(if (message.isLong()) LONG_MESSAGE_MILLIS else MESSAGE_MILLIS)
        dismiss()
    }
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { it / 3 },
        exit = fadeOut(tween(150)),
    ) {
        shown?.let {
            Snackbar(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(it.text())
            }
        }
    }
}
