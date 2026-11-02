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
import java.time.Clock

/** What was copied (AF-09, UI-02). */
enum class CopiedValue { USERNAME, PASSWORD }

/** Clipboard state for the snackbar countdown (UiMessage.Copied / ClipboardCleared). */
sealed interface ClipboardStatus {
    /** Nothing pending, or the guard stopped retrying a value that something keeps putting back. */
    data object Idle : ClipboardStatus

    /** Our value is in the clipboard; it is cleared in [secondsLeft] seconds. Updated every second. */
    data class Holding(val value: CopiedValue, val secondsLeft: Int) : ClipboardStatus

    /**
     * The value was cleared (timeout or lock). Stays until the next copy. With the app in front this
     * is verified (the clip no longer holds the value). In the background the clip cannot be read: the
     * clear was issued but not verified, and the platform looks again when the app is back in front.
     */
    data class Cleared(val value: CopiedValue) : ClipboardStatus
}

/**
 * Clipboard as last resort (AF-09): copies with [ClipDescription.EXTRA_IS_SENSITIVE]
 * and clears after the configured time, and on lock.
 *
 * Android lets only the focused app read the clipboard; in the background
 * (the usual case, the user pastes elsewhere) the description reads as null.
 * Then it clears anyway: leaving a password behind is worse than removing
 * something copied in the same few seconds. Clearing needs no focus. The clear overwrites the
 * clip with an empty sensitive one first and then clears it, for services that handle only one
 * of the two.
 *
 * In the foreground the clip is checked: it is cleared when it still holds our
 * value, whoever owns it now (the emulator's host clipboard sync and some keyboards
 * put the value back under another label), and kept when it is something else.
 * Only a keyed digest of the value is kept for that ([ClipboardMac]; plain SHA-256 in memory if the keystore key
 * is unavailable). The result is verified, also half a
 * second later (a restore comes asynchronously), and a clip that does not go away is
 * cleared again with a back-off ([ClipboardPlan.confirmDelay]) until [giveUpAfterMillis]
 * after the original expiry; then the status is [ClipboardStatus.Idle].
 *
 * A clear that could not be verified (background) is issued once more after two seconds and
 * remembered as [PendingClear] (keyed digest, never the value) in [markers]. It is looked at again
 * when the app is in front ([checkPending]): if the clip still holds the value it is cleared,
 * and the marker is dropped as soon as a readable clip does not hold it. A backup alarm
 * ([alarms]) and the check at process start clear a value whose countdown died with the process.
 */
internal class ClipboardGuard(
    context: Context,
    private val scope: CoroutineScope,
    private val elapsedMillis: () -> Long,
    private val clock: Clock = Clock.systemUTC(),
    private val mac: ClipboardMac = ClipboardMac.Plain,
    private val markers: ClipboardMarkers = ClipboardMarkers.None,
    private val alarms: ClipboardAlarms = ClipboardAlarms.None,
    private val giveUpAfterMillis: Long = ClipboardPlan.GIVE_UP_AFTER_MILLIS,
) {
    private val manager: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)
    private val label: String = context.getString(R.string.platform_clip_label)

    private val mutableStatus = MutableStateFlow<ClipboardStatus>(ClipboardStatus.Idle)
    val status: StateFlow<ClipboardStatus> = mutableStatus.asStateFlow()

    /** Guards everything below and the clipboard calls, so a copy and a clear never interleave. */
    private val lock = Any()
    private var holding: CopiedValue? = null
    private var ourTimestamp: Long? = null

    /**
     * Digest of the copied text, to recognize it under another label; never the text itself. Keyed ([mac]) when
     * [ourKeyed], else plain SHA-256 (the key was unavailable, nothing is persisted). Null: a marker without
     * digest was taken over, the clip is cleared blindly.
     */
    private var ourDigest: ByteArray? = null
    private var ourKeyed = true

    /** When the held value is due (epoch milliseconds) and when the re-clearing stops (elapsed milliseconds). */
    private var dueAt = 0L
    private var giveUpAt = 0L
    private var countdown: Job? = null
    private var focusCheck: Job? = null

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
            holding = value
            ourTimestamp = stamp
            val keyed = mac.mac(text)
            ourKeyed = keyed != null
            ourDigest = keyed ?: digestOf(text)
            dueAt = clock.millis() + timeout.duration.toMillis()
            giveUpAt = end + giveUpAfterMillis
            countdown?.cancel()
            focusCheck?.cancel()
            saveMarker(issued = false)
            countdown = startCountdown(value, end, ++token)
        }
        return true
    }

    /** Publishes the countdown and clears at its end. Caller holds [lock] and has set [token]. */
    private fun startCountdown(value: CopiedValue, end: Long, mine: Long): Job {
        mutableStatus.value = ClipboardStatus.Holding(value, secondsUntil(end))
        return scope.launch {
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

    /**
     * Clears our clip now (on lock): the copy that is held, or else a value whose clear was
     * issued earlier without being able to look.
     */
    fun clearNow() {
        synchronized(lock) {
            if (holding != null) {
                clear(onlyCopy = null)
            } else {
                pendingMarker()?.let { reclear(it) }
            }
        }
    }

    /**
     * Looks at a value that is pending outside the running countdown: after a process start, from
     * the backup alarm, and when the app comes to the front (a clip can only be read with focus,
     * which follows onResume shortly). Cheap without a marker. Never throws.
     */
    fun checkPending() {
        try {
            synchronized(lock) { checkPendingLocked() }
        } catch (_: Exception) {
            // The clipboard service or the marker file failed; the next call tries again.
        }
    }

    private fun checkPendingLocked() {
        if (holding != null) return // A countdown of this process acts on its own.
        val p = pendingMarker() ?: return
        when (ClipboardPlan.pendingAction(p, clock.millis())) {
            PendingAction.WAIT -> resumeCountdown(p)
            PendingAction.CLEAR -> takeOver(p)
            PendingAction.VERIFY -> if (isReadable()) verify(p) else retryWhenFocused()
            PendingAction.DROP -> dropMarker()
        }
    }

    /** The process restarted before the value was due: count down again (and arm the alarm, a reboot loses it). */
    private fun resumeCountdown(p: PendingClear) {
        val left = (p.dueAt - clock.millis()).coerceAtLeast(0)
        val end = elapsedMillis() + left
        adopt(p)
        giveUpAt = end + giveUpAfterMillis
        alarms.schedule(p.dueAt + ClipboardPlan.ALARM_SLACK_MILLIS)
        countdown = startCountdown(p.value, end, ++token)
    }

    /** Makes the marker's value the held one. Caller holds [lock]. */
    private fun adopt(p: PendingClear) {
        countdown?.cancel()
        holding = p.value
        ourTimestamp = null
        ourDigest = p.digest()
        ourKeyed = true
        dueAt = p.dueAt
        giveUpAt = elapsedMillis() + giveUpAfterMillis
    }

    /** The marker's value is due (or came back): clear it like a copy that ran out. */
    private fun takeOver(p: PendingClear) {
        adopt(p)
        token++
        clear(onlyCopy = null)
    }

    /** The clip is readable: clear the pending value if it is still there, else forget it. */
    private fun verify(p: PendingClear) {
        val cm = manager ?: return
        val digest = p.digest() ?: return dropMarker()
        val holds = try {
            holdsDigest(cm, digest, keyed = true)
        } catch (_: Exception) {
            return
        }
        if (holds) takeOver(p) else dropMarker()
    }

    /** One more clear of an issued value: after a look if the clip can be read, else blind (shortly after the issue). */
    private fun reclear(p: PendingClear) {
        val cm = manager ?: return
        if (isReadable()) {
            verify(p)
        } else if (ClipboardPlan.mayClearBlind(p, clock.millis())) {
            try {
                overwriteAndClear(cm)
            } catch (_: Exception) {
                // The next check tries again.
            }
        }
    }

    /** The app has just come to the front: the clip becomes readable when the window has focus. */
    private fun retryWhenFocused() {
        focusCheck?.cancel()
        focusCheck = scope.launch {
            for (wait in ClipboardPlan.FOCUS_RETRY_DELAYS_MILLIS) {
                delay(wait)
                synchronized(lock) {
                    if (holding != null) return@launch
                    val p = pendingMarker() ?: return@launch
                    if (isReadable()) {
                        verify(p)
                        return@launch
                    }
                }
            }
        }
    }

    /** Looks once more shortly after a clear that could not be verified. */
    private fun reclearLater(mine: Long): Job = scope.launch {
        delay(ClipboardPlan.RECLEAR_DELAY_MILLIS)
        synchronized(lock) {
            if (token != mine || holding != null) return@launch
            pendingMarker()?.let { reclear(it) }
        }
    }

    /**
     * Clears our clip; with [onlyCopy] only if that copy is still the current one (a stale countdown does nothing).
     * Reports [ClipboardStatus.Cleared] when the clip is verified free of our value, or could not be read (background,
     * then the clear is remembered and checked again). If it held our value, that is checked again after a moment
     * ([confirmClear]): something may put it back.
     */
    private fun clear(onlyCopy: Long?) {
        synchronized(lock) {
            if (onlyCopy != null && token != onlyCopy) return
            val expected = holding ?: return
            countdown?.cancel()
            countdown = null
            when (removeFromClipboard(confirming = false)) {
                Removal.NOTHING_LEFT -> finishVerified(expected)
                Removal.UNVERIFIABLE -> finishIssued(ClipboardStatus.Cleared(expected), reclear = true)
                Removal.REMOVED, Removal.STILL_THERE -> {
                    mutableStatus.value = ClipboardStatus.Holding(expected, 0)
                    countdown = confirmClear(token)
                }
            }
        }
    }

    /**
     * Looks again with a back-off ([ClipboardPlan.confirmDelay]) and clears again if the value is back (a keyboard or
     * clipboard sync that restores it), until a look finds nothing left to clear. Gives up at [giveUpAt]: something
     * outside the app keeps putting the value back, or the clipboard service fails, and retrying forever would only
     * keep a "0 s" countdown on screen. The status is then [ClipboardStatus.Idle] (not Cleared, which would be
     * untrue); the clear stays remembered and is tried again when the app is in front and on lock.
     */
    private fun confirmClear(mine: Long): Job = scope.launch {
        var attempt = 0
        while (true) {
            delay(ClipboardPlan.confirmDelay(attempt++))
            synchronized(lock) {
                if (token != mine) return@launch
                val expected = holding ?: return@launch
                when (removeFromClipboard(confirming = true)) {
                    Removal.NOTHING_LEFT -> {
                        countdown = null
                        finishVerified(expected)
                        return@launch
                    }
                    Removal.UNVERIFIABLE -> {
                        countdown = null
                        finishIssued(ClipboardStatus.Cleared(expected), reclear = true)
                        return@launch
                    }
                    Removal.REMOVED, Removal.STILL_THERE -> {
                        if (elapsedMillis() >= giveUpAt) {
                            countdown = null
                            finishIssued(ClipboardStatus.Idle, reclear = false)
                            return@launch
                        }
                    }
                }
            }
        }
    }

    /** The clip is verified free of our value: nothing is pending any more. Caller holds [lock]. */
    private fun finishVerified(expected: CopiedValue) {
        forgetHeld()
        dropMarker()
        mutableStatus.value = ClipboardStatus.Cleared(expected)
    }

    /** The clear was issued but could not be verified: remember it for the next look. Caller holds [lock]. */
    private fun finishIssued(status: ClipboardStatus, reclear: Boolean) {
        saveMarker(issued = true)
        forgetHeld()
        mutableStatus.value = status
        if (reclear) countdown = reclearLater(token)
    }

    private fun forgetHeld() {
        holding = null
        ourTimestamp = null
        ourDigest = null
    }

    // ---- the marker ----

    /** The pending marker, read from [markers] each time (a missing file is cheap to look at). */
    private fun pendingMarker(): PendingClear? = try {
        markers.read()
    } catch (_: Exception) {
        null
    }

    /** Stores the held value as pending; arms the alarm while it is not issued, cancels it after. Caller holds [lock]. */
    private fun saveMarker(issued: Boolean) {
        val value = holding ?: return
        // Only a keyed digest is persisted; without it an issued clear has nothing to verify against later.
        val digest = ourDigest?.takeIf { ourKeyed }?.let(ClipboardPlan::hex)
        if (issued && digest == null) return dropMarker()
        val marker = PendingClear(value, dueAt, digest, issued)
        try {
            markers.write(marker)
        } catch (_: Exception) {
            // Without the file the countdown still works; only the cover for a process death is lost.
        }
        if (issued) alarms.cancel() else alarms.schedule(dueAt + ClipboardPlan.ALARM_SLACK_MILLIS)
    }

    private fun dropMarker() {
        try {
            markers.write(null)
        } catch (_: Exception) {
            // A stale marker is dropped by its age.
        }
        alarms.cancel()
    }

    // ---- the clipboard ----

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
        if (ourDigest == null) {
            // A marker without digest: nothing to recognize the value by, so clear whatever is there.
            return try {
                overwriteAndClear(cm)
                Removal.UNVERIFIABLE
            } catch (_: Exception) {
                Removal.STILL_THERE
            }
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
            overwriteAndClear(cm)
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

    /** Replaces the clip with an empty sensitive one, then clears it: some services honour only one of the two. */
    private fun overwriteAndClear(cm: ClipboardManager) {
        try {
            cm.setPrimaryClip(sensitiveClip("", ""))
        } catch (_: Exception) {
            // The clear below still runs.
        }
        cm.clearPrimaryClip()
    }

    private fun isReadable(): Boolean = try {
        manager?.primaryClipDescription != null
    } catch (_: Exception) {
        false
    }

    /** True when an item of the (readable) primary clip has our text. */
    private fun holdsOurValue(cm: ClipboardManager): Boolean {
        val digest = ourDigest ?: return false
        return holdsDigest(cm, digest, ourKeyed)
    }

    private fun holdsDigest(cm: ClipboardManager, digest: ByteArray, keyed: Boolean): Boolean {
        val clip = cm.primaryClip ?: return false
        for (i in 0 until clip.itemCount) {
            val text = clip.getItemAt(i).text ?: continue
            val other = (if (keyed) mac.mac(text) else digestOf(text)) ?: continue
            if (MessageDigest.isEqual(digest, other)) return true
        }
        return false
    }

    private fun secondsUntil(end: Long): Int = ((end - elapsedMillis() + 999) / 1000).toInt().coerceAtLeast(0)

    internal companion object {
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
