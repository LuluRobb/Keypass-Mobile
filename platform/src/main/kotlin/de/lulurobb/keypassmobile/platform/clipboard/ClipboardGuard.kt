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
import java.nio.CharBuffer
import java.security.MessageDigest

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
 * Android lets only the focused app read the clipboard; in the background
 * (the usual case, the user pastes elsewhere) the description reads as null.
 * Then it clears anyway: leaving a password behind is worse than removing
 * something copied in the same few seconds. Clearing needs no focus.
 *
 * In the foreground the clip is checked: it is cleared when it still holds our
 * value, whoever owns it now (the emulator's host clipboard sync and some keyboards
 * put the value back under another label), and kept when it is something else.
 * Only a digest of the value is kept for that. The result is verified, also half a
 * second later (a restore comes asynchronously), and a clip that does not go away is
 * cleared again: [ClipboardStatus.Cleared] is only reported once the clip no longer
 * holds our value (or could not be read, in the background, where the clear was
 * issued anyway). After ten fruitless looks the guard gives up and reports
 * [ClipboardStatus.Idle].
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

    /** SHA-256 of the copied text, to recognize it under another label; never the text itself. */
    private var ourDigest: ByteArray? = null
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
            ourDigest = digestOf(text)
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

    /**
     * Clears our clip; with [onlyCopy] only if that copy is still the current one (a stale countdown does nothing).
     * Reports [ClipboardStatus.Cleared] when the clip is verified free of our value, or could not be read (background).
     * If it held our value, that is checked again after a moment ([confirmClear]): something may put it back.
     */
    private fun clear(onlyCopy: Long?) {
        synchronized(lock) {
            if (onlyCopy != null && token != onlyCopy) return
            val expected = holding ?: return
            countdown?.cancel()
            countdown = null
            when (removeFromClipboard(confirming = false)) {
                Removal.NOTHING_LEFT, Removal.UNVERIFIABLE -> finishClear(expected)
                Removal.REMOVED, Removal.STILL_THERE -> {
                    mutableStatus.value = ClipboardStatus.Holding(expected, 0)
                    countdown = confirmClear(token)
                }
            }
        }
    }

    /**
     * Looks again every half second and clears again if the value is back (a keyboard or
     * clipboard sync that restores it), until a look finds nothing left to clear. Gives up
     * after [MAX_CONFIRMS] looks: something outside the app keeps putting the value back, or
     * the clipboard service fails, and retrying forever would only keep a "0 s" countdown on
     * screen. The status is then [ClipboardStatus.Idle] (not Cleared, which would be untrue);
     * the copy stays pending, so a lock tries once more ([clearNow]).
     */
    private fun confirmClear(mine: Long): Job = scope.launch {
        var looks = 0
        while (true) {
            delay(CONFIRM_MILLIS)
            synchronized(lock) {
                if (token != mine) return@launch
                val expected = holding ?: return@launch
                when (removeFromClipboard(confirming = true)) {
                    Removal.NOTHING_LEFT, Removal.UNVERIFIABLE -> {
                        countdown = null
                        finishClear(expected)
                        return@launch
                    }
                    Removal.REMOVED, Removal.STILL_THERE -> {
                        if (++looks >= MAX_CONFIRMS) {
                            countdown = null
                            mutableStatus.value = ClipboardStatus.Idle
                            return@launch
                        }
                    }
                }
            }
        }
    }

    /** Caller holds [lock]. */
    private fun finishClear(expected: CopiedValue) {
        holding = null
        ourTimestamp = null
        ourDigest = null
        mutableStatus.value = ClipboardStatus.Cleared(expected)
    }

    private enum class Removal {
        /** Nothing of ours was in the clip (never, empty, or replaced by something else). */
        NOTHING_LEFT,

        /** The clip is not readable (background): the clear was issued, the result cannot be checked. */
        UNVERIFIABLE,

        /** Our value was in the clip and a look right after the clear finds it gone. */
        REMOVED,

        /** Our value is still in the clip after the clear, or the clear failed. */
        STILL_THERE,
    }

    /**
     * Clears the primary clip if it is ours and looks again. [confirming]: a later look after a
     * clear, which always reads the content, so an empty clip that still carries our label and
     * timestamp is no reason to clear again. Caller holds [lock].
     */
    private fun removeFromClipboard(confirming: Boolean): Removal {
        val cm = manager ?: return Removal.NOTHING_LEFT
        val description = try {
            cm.primaryClipDescription
        } catch (_: Exception) {
            null
        }
        try {
            val looksLikeOurs = description != null && description.label?.toString() == label &&
                (ourTimestamp == null || description.timestamp == ourTimestamp)
            // The content is read when the clip does not look like ours, or after a clear.
            val holdsValue = if (description != null && (confirming || !looksLikeOurs)) holdsOurValue(cm) else null
            val clear = shouldClear(
                readable = description != null,
                clipLabel = description?.label?.toString(),
                clipTimestamp = description?.timestamp ?: 0L,
                ourLabel = label,
                ourTimestamp = ourTimestamp,
                holdsOurValue = holdsValue,
            )
            if (!clear) return Removal.NOTHING_LEFT
            cm.clearPrimaryClip()
        } catch (_: Exception) {
            // Clipboard service unavailable: not cleared, so not reported as cleared.
            return Removal.STILL_THERE
        }
        if (description == null) return Removal.UNVERIFIABLE
        return try {
            if (cm.primaryClipDescription != null && holdsOurValue(cm)) Removal.STILL_THERE else Removal.REMOVED
        } catch (_: Exception) {
            Removal.UNVERIFIABLE
        }
    }

    /** True when an item of the (readable) primary clip has our text. */
    private fun holdsOurValue(cm: ClipboardManager): Boolean {
        val digest = ourDigest ?: return false
        val clip = cm.primaryClip ?: return false
        for (i in 0 until clip.itemCount) {
            val text = clip.getItemAt(i).text ?: continue
            if (MessageDigest.isEqual(digest, digestOf(text))) return true
        }
        return false
    }

    private fun secondsUntil(end: Long): Int = ((end - elapsedMillis() + 999) / 1000).toInt().coerceAtLeast(0)

    internal companion object {
        private const val CONFIRM_MILLIS = 500L

        /** Looks after the first clear (5 s) before the guard stops clearing again. */
        private const val MAX_CONFIRMS = 10

        /**
         * Whether to clear: always when the clip cannot be read (background or
         * empty). [holdsOurValue] null: the content was not read, then only a clip with
         * our label and system timestamp is cleared. Otherwise the content decides, under any
         * label: our value is cleared, anything else (also an empty clip) stays.
         */
        fun shouldClear(
            readable: Boolean,
            clipLabel: String?,
            clipTimestamp: Long,
            ourLabel: String,
            ourTimestamp: Long?,
            holdsOurValue: Boolean? = null,
        ): Boolean = when {
            !readable -> true
            holdsOurValue != null -> holdsOurValue
            clipLabel != ourLabel -> false
            else -> ourTimestamp == null || clipTimestamp == ourTimestamp
        }

        /** SHA-256 of the UTF-8 text, without making a String of a password. */
        fun digestOf(text: CharSequence): ByteArray {
            val bytes = Charsets.UTF_8.encode(CharBuffer.wrap(text))
            try {
                return MessageDigest.getInstance("SHA-256").run {
                    update(bytes)
                    digest()
                }
            } finally {
                if (bytes.hasArray()) bytes.array().fill(0)
            }
        }

        /** Plain text clip marked sensitive, so the system hides its preview (AF-09). */
        fun sensitiveClip(label: String, text: CharSequence): ClipData = ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
    }
}
