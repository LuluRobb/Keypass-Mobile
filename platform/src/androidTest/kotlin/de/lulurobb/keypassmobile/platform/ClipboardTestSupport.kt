package de.lulurobb.keypassmobile.platform

import de.lulurobb.keypassmobile.platform.clipboard.ClipboardAlarms
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardMac
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardPlan
import de.lulurobb.keypassmobile.platform.clipboard.CopiedValue
import de.lulurobb.keypassmobile.platform.clipboard.PendingClear
import java.util.concurrent.CopyOnWriteArrayList

/** Remembers what the guard asked of the alarm service, without scheduling anything. */
class RecordingAlarms : ClipboardAlarms {
    val scheduled = CopyOnWriteArrayList<Long>()

    @Volatile
    var cancels = 0

    override fun schedule(atEpochMillis: Long) {
        scheduled += atEpochMillis
    }

    override fun cancel() {
        cancels++
    }
}

/** A marker for [text] that is due [dueInMillis] from now (negative: overdue), recognized through [mac]. */
internal fun pendingFor(
    text: String,
    dueInMillis: Long,
    issued: Boolean,
    value: CopiedValue = CopiedValue.PASSWORD,
    mac: ClipboardMac = ClipboardMac.Plain,
) = PendingClear(value, System.currentTimeMillis() + dueInMillis, ClipboardPlan.hex(mac.mac(text)!!), issued)
