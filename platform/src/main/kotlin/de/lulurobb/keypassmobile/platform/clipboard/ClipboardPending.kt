package de.lulurobb.keypassmobile.platform.clipboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.lulurobb.keypassmobile.platform.KpmPlatform
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** File name of the pending-clear marker in the app's key directory. */
internal const val PENDING_CLEAR_FILE = "clipboard-pending.properties"

/**
 * A copied value that must leave the clipboard, kept outside the process so that a
 * process death, a reboot or a clip that could not be checked in the background does not
 * leave it there. Holds a keyed digest ([ClipboardMac]) of the value, never the value itself.
 *
 * @property dueAt When the value is due to be cleared (epoch milliseconds).
 * @property digestHex HMAC of the copied text, hex; null when the keystore key was unavailable at the copy:
 *   the clip is then cleared blindly at the due time and cannot be verified.
 * @property issued The clear was issued but could not be verified (the clip is unreadable without
 *   focus); it is checked again when the app is in front.
 */
internal data class PendingClear(
    val value: CopiedValue,
    val dueAt: Long,
    val digestHex: String?,
    val issued: Boolean,
) {
    fun digest(): ByteArray? = digestHex?.let(ClipboardPlan::fromHex)
}

/** What to do with a [PendingClear] now. */
internal enum class PendingAction {
    /** Not due yet: the countdown or the alarm will act. */
    WAIT,

    /** Due and never issued (the process died before): clear now. */
    CLEAR,

    /** Issued before: look at the clip when it can be read, clear again if the value is back. */
    VERIFY,

    /** Too old to matter: forget it. */
    DROP,
}

/** Timing and decisions of the clipboard guard, free of Android so they can be tested. */
internal object ClipboardPlan {
    /** The guard keeps re-clearing a value that comes back until this long after the original expiry. */
    const val GIVE_UP_AFTER_MILLIS = 60_000L

    /** The backup alarm fires this long after the due time, so a living process always acts first. */
    const val ALARM_SLACK_MILLIS = 2_000L

    /** An issued clear is checked again at most this long after the due time (the marker is dropped then). */
    const val VERIFY_MAX_AGE_MILLIS = 10 * 60_000L

    /** A clip that cannot be read is cleared blindly again only this long after an issued clear. */
    const val BLIND_WINDOW_MILLIS = 2 * 60_000L

    /** The clear is issued once more this long after it was issued without being able to look. */
    const val RECLEAR_DELAY_MILLIS = 2_000L

    /** Looks at the clip when the app has just come to the front and cannot read it yet (focus follows onResume). */
    val FOCUS_RETRY_DELAYS_MILLIS = longArrayOf(300, 700, 1_500, 3_000)

    private val confirmDelays = longArrayOf(500, 1_000, 2_000, 5_000, 10_000)

    /** Wait before look number [attempt] (0 based) after a clear: 0.5, 1, 2, 5, then every 10 seconds. */
    fun confirmDelay(attempt: Int): Long = confirmDelays[attempt.coerceIn(0, confirmDelays.lastIndex)]

    fun pendingAction(pending: PendingClear, now: Long): PendingAction = when {
        !pending.issued && now < pending.dueAt -> PendingAction.WAIT
        !pending.issued -> PendingAction.CLEAR
        // Nothing to compare the clip with.
        pending.digestHex == null -> PendingAction.DROP
        now - pending.dueAt > VERIFY_MAX_AGE_MILLIS -> PendingAction.DROP
        else -> PendingAction.VERIFY
    }

    /** An unreadable clip may be cleared without looking: always before the first issue, shortly after it. */
    fun mayClearBlind(pending: PendingClear, now: Long): Boolean =
        !pending.issued || now - pending.dueAt <= BLIND_WINDOW_MILLIS

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** Persists the [PendingClear]; no marker means nothing is pending. */
internal interface ClipboardMarkers {
    fun read(): PendingClear?

    /** Stores [pending]; null deletes it. @throws IOException when it cannot be written. */
    fun write(pending: PendingClear?)

    /** No persistence (tests of other things). */
    object None : ClipboardMarkers {
        override fun read(): PendingClear? = null

        override fun write(pending: PendingClear?) = Unit
    }
}

/** [ClipboardMarkers] in one small file in the app's private no-backup directory (SE-08). */
internal class FileClipboardMarkers(private val file: File) : ClipboardMarkers {
    private val temp = File(file.parentFile, file.name + ".tmp")

    /** An unreadable file is deleted: without the digest there is nothing to check against. */
    override fun read(): PendingClear? {
        if (!file.exists()) return null
        return try {
            val p = Properties()
            file.inputStream().use { p.load(it) }
            val digest = p.getProperty("digest")
            if (digest != null) ClipboardPlan.fromHex(digest)
            PendingClear(
                value = CopiedValue.valueOf(p.getProperty("value") ?: throw IOException("no value")),
                dueAt = p.getProperty("dueAt")?.toLong() ?: throw IOException("no due time"),
                digestHex = digest,
                issued = p.getProperty("issued") == "true",
            )
        } catch (_: Exception) {
            try {
                Files.deleteIfExists(file.toPath())
            } catch (_: IOException) {
                // Nothing more to do.
            }
            null
        }
    }

    override fun write(pending: PendingClear?) {
        if (pending == null) {
            Files.deleteIfExists(file.toPath())
            return
        }
        file.parentFile?.mkdirs()
        val p = Properties()
        p.setProperty("value", pending.value.name)
        p.setProperty("dueAt", pending.dueAt.toString())
        pending.digestHex?.let { p.setProperty("digest", it) }
        p.setProperty("issued", pending.issued.toString())
        try {
            temp.outputStream().use { out -> p.store(out, "KeyPass Mobile clipboard clear pending") }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }
}

/** Wakes the process to clear a value that its own countdown did not clear (process death, deep sleep). */
internal interface ClipboardAlarms {
    /** Fires [ClipboardClearReceiver] at [atEpochMillis]; replaces an earlier alarm. Never throws. */
    fun schedule(atEpochMillis: Long)

    /** Never throws. */
    fun cancel()

    object None : ClipboardAlarms {
        override fun schedule(atEpochMillis: Long) = Unit

        override fun cancel() = Unit
    }
}

/**
 * [ClipboardAlarms] with [AlarmManager.setAndAllowWhileIdle]: inexact, needs no permission
 * (an exact alarm would need SCHEDULE_EXACT_ALARM). In deep sleep it may come late; it is
 * only the backup of the countdown.
 */
internal class SystemClipboardAlarms(private val context: Context) : ClipboardAlarms {
    override fun schedule(atEpochMillis: Long) {
        try {
            context.getSystemService(AlarmManager::class.java)
                ?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, pendingIntent())
        } catch (_: Exception) {
            // The in-process countdown and the check at the next start remain.
        }
    }

    override fun cancel() {
        try {
            val intent = pendingIntent()
            context.getSystemService(AlarmManager::class.java)?.cancel(intent)
            intent.cancel()
        } catch (_: Exception) {
            // Nothing scheduled or the service is gone.
        }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CLEAR,
        Intent(context, ClipboardClearReceiver::class.java).setAction(ClipboardClearReceiver.ACTION_CLEAR),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val REQUEST_CLEAR = 3
    }
}

/** The backup alarm of the clipboard guard: lets the platform look at the pending clear (see [ClipboardGuard.checkPending]). */
class ClipboardClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CLEAR) return
        KpmPlatform.get(context).checkClipboard()
    }

    internal companion object {
        const val ACTION_CLEAR = "de.lulurobb.keypassmobile.platform.action.CLEAR_CLIPBOARD"
    }
}
