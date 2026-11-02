package de.lulurobb.keypassmobile.platform.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import de.lulurobb.keypassmobile.core.settings.ClipboardTimeout
import de.lulurobb.keypassmobile.platform.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What was copied (AF-09, UI-02). */
enum class CopiedValue { USERNAME, PASSWORD }

/** Clipboard state for the snackbar countdown (UiMessage.Copied / ClipboardCleared). */
sealed interface ClipboardStatus {
    data object Idle : ClipboardStatus

    /** Our value is in the clipboard; it is cleared in [secondsLeft] seconds. Updated every second. */
    data class Holding(val value: CopiedValue, val secondsLeft: Int) : ClipboardStatus

    /** The value was cleared (timeout or lock). Stays until the next copy. */
    data class Cleared(val value: CopiedValue) : ClipboardStatus
}

/**
 * Clipboard as last resort (AF-09): copies with [ClipDescription.EXTRA_IS_SENSITIVE]
 * and clears after the configured time, and on lock.
 *
 * Clears only if the clip is still ours (same label and system timestamp).
 * Android lets only the focused app read the clipboard; in the background
 * (the usual case, the user pastes elsewhere) the description reads as null.
 * Then it clears anyway: leaving a password behind is worse than removing
 * something copied in the same few seconds. Clearing needs no focus.
 */
internal class ClipboardGuard(
    context: Context,
    private val scope: CoroutineScope,
    private val elapsedMillis: () -> Long,
) {
    private val manager: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)
    private val label: String = context.getString(R.string.platform_clip_label)

    private val mutableStatus = MutableStateFlow<ClipboardStatus>(ClipboardStatus.Idle)
    val status: StateFlow<ClipboardStatus> = mutableStatus.asStateFlow()

    /** Guards everything below and the clipboard calls, so a copy and a clear never interleave. */
    private val lock = Any()
    private var holding: CopiedValue? = null
    private var ourTimestamp: Long? = null
    private var countdown: Job? = null

    /** Changes with every copy; a countdown acts only while its copy is still the current one. */
    private var token = 0L

    /**
     * Copies [text] as sensitive clip and starts the countdown. Returns false if there
     * is no clipboard, or when [allowed] is false. [allowed] (the session is still
     * unlocked) is checked inside the guard's lock: a lock that completes meanwhile
     * clears under the same lock, so the secret never lands after the clear (SE-04).
     */
    fun copy(value: CopiedValue, text: CharSequence, timeout: ClipboardTimeout, allowed: () -> Boolean = { true }): Boolean {
        val cm = manager ?: return false
        synchronized(lock) {
            if (!allowed()) return false
            cm.setPrimaryClip(sensitiveClip(label, text))
            // The app is in front while copying, so it may read its own clip back.
            val stamp = try {
                cm.primaryClipDescription?.takeIf { it.label?.toString() == label }?.timestamp
            } catch (_: Exception) {
                null
            }
            val end = elapsedMillis() + timeout.duration.toMillis()
            val mine = ++token
            holding = value
            ourTimestamp = stamp
            countdown?.cancel()
            mutableStatus.value = ClipboardStatus.Holding(value, secondsUntil(end))
            countdown = scope.launch {
                while (true) {
                    val left = end - elapsedMillis()
                    if (left <= 0) break
                    synchronized(lock) {
                        if (token != mine || holding == null) return@launch
                        mutableStatus.value = ClipboardStatus.Holding(value, secondsUntil(end))
                    }
                    // Ticks also catch up quickly after deep sleep, which delay() does not count.
                    delay(left.coerceAtMost(1_000L))
                }
                clear(onlyCopy = mine)
            }
        }
        return true
    }

    /** Clears our clip now, if one is pending (on lock). */
    fun clearNow() = clear(onlyCopy = null)

    /** Clears our clip; with [onlyCopy] only if that copy is still the current one (a stale countdown does nothing). */
    private fun clear(onlyCopy: Long?) {
        synchronized(lock) {
            if (onlyCopy != null && token != onlyCopy) return
            val expected = holding ?: return
            countdown?.cancel()
            countdown = null
            holding = null
            val stamp = ourTimestamp
            ourTimestamp = null
            try {
                val cm = manager ?: return
                val description = try {
                    cm.primaryClipDescription
                } catch (_: Exception) {
                    null
                }
                val clear = shouldClear(
                    readable = description != null,
                    clipLabel = description?.label?.toString(),
                    clipTimestamp = description?.timestamp ?: 0L,
                    ourLabel = label,
                    ourTimestamp = stamp,
                )
                if (clear) cm.clearPrimaryClip()
            } catch (_: Exception) {
                // Clipboard service unavailable: nothing more to do.
            } finally {
                mutableStatus.value = ClipboardStatus.Cleared(expected)
            }
        }
    }

    private fun secondsUntil(end: Long): Int = ((end - elapsedMillis() + 999) / 1000).toInt().coerceAtLeast(0)

    internal companion object {
        /**
         * Whether to clear: always when the clip cannot be read (background or
         * empty); otherwise only when label and system timestamp are ours.
         */
        fun shouldClear(
            readable: Boolean,
            clipLabel: String?,
            clipTimestamp: Long,
            ourLabel: String,
            ourTimestamp: Long?,
        ): Boolean = when {
            !readable -> true
            clipLabel != ourLabel -> false
            else -> ourTimestamp == null || clipTimestamp == ourTimestamp
        }

        /** Plain text clip marked sensitive, so the system hides its preview (AF-09). */
        fun sensitiveClip(label: String, text: CharSequence): ClipData = ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
    }
}
