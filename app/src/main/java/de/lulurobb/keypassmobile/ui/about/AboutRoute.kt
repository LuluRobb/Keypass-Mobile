package de.lulurobb.keypassmobile.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.ui.common.UiMessage

/**
 * About screen on the shared messages. "Infos kopieren" puts the summary on the clipboard as a plain
 * clip, not marked sensitive (it holds nothing secret, unlike the copied passwords). The links are
 * activity-only, so the caller passes [onOpenLink] in. [onOpenLicenses] opens the licenses list.
 */
@Composable
fun AboutRoute(
    info: AboutInfo,
    messages: AppMessages,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val label = stringResource(R.string.app_name)
    val message by messages.message.collectAsState()
    AboutScreen(
        info = info,
        onBack = onBack,
        onOpenLink = onOpenLink,
        onOpenLicenses = onOpenLicenses,
        onCopyInfo = { text ->
            val copied = try {
                clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
                clipboard != null
            } catch (_: RuntimeException) {
                false
            }
            if (copied) messages.post(UiMessage.InfoCopied)
        },
        message = message,
        onMessageDismissed = messages::dismiss,
    )
}
