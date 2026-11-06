package de.lulurobb.keypassmobile.platform.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import de.lulurobb.keypassmobile.platform.R

/**
 * Clipboard as last resort (AF-09): copies a username or password as a clip marked
 * sensitive ([ClipDescription.EXTRA_IS_SENSITIVE]), so the system hides its preview.
 *
 * The app never clears the clipboard, neither after a time nor on lock (decision of
 * 06.11.2026, see AF-09): keyboard clipboard histories, for example the Samsung keyboard's,
 * keep the value anyway and cannot be cleared by the app.
 */
internal class SensitiveClipboard(context: Context) {
    private val manager: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)
    private val label: String = context.getString(R.string.platform_clip_label)

    /** Copies [text]. False when there is no clipboard service or it fails. */
    fun copy(text: CharSequence): Boolean {
        val cm = manager ?: return false
        return try {
            cm.setPrimaryClip(sensitiveClip(label, text))
            true
        } catch (_: Exception) {
            false
        }
    }

    internal companion object {
        /** Plain text clip marked sensitive, so the system hides its preview (AF-09). */
        fun sensitiveClip(label: String, text: CharSequence): ClipData = ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
    }
}
