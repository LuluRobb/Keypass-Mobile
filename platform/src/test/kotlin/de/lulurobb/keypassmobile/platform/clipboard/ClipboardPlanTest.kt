package de.lulurobb.keypassmobile.platform.clipboard

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipboardPlanTest {

    @TempDir
    lateinit var dir: File

    private val NL = System.lineSeparator()
    private val due = 1_000_000L
    private val digest = ClipboardPlan.hex(ClipboardGuard.digestOf("geheim-123"))

    private fun pending(issued: Boolean = false) = PendingClear(CopiedValue.PASSWORD, due, digest, issued)

    @Test
    fun `confirm looks back off from half a second to ten seconds and stay there`() {
        assertEquals(listOf(500L, 1_000L, 2_000L, 5_000L, 10_000L), (0..4).map { ClipboardPlan.confirmDelay(it) })
        assertEquals(10_000L, ClipboardPlan.confirmDelay(5))
        assertEquals(10_000L, ClipboardPlan.confirmDelay(500))
        assertEquals(500L, ClipboardPlan.confirmDelay(-1))
        // Twelve looks reach past the give-up time after the original expiry.
        val total = (0 until 12).sumOf { ClipboardPlan.confirmDelay(it) }
        assertTrue(total >= ClipboardPlan.GIVE_UP_AFTER_MILLIS, "12 looks span at least the give-up time: $total")
    }

    @Test
    fun `a pending clear waits until it is due, then clears, also long after`() {
        assertEquals(PendingAction.WAIT, ClipboardPlan.pendingAction(pending(), due - 1))
        assertEquals(PendingAction.CLEAR, ClipboardPlan.pendingAction(pending(), due))
        assertEquals(PendingAction.CLEAR, ClipboardPlan.pendingAction(pending(), due + 3 * 3_600_000L))
    }

    @Test
    fun `an issued clear is verified for an hour, then forgotten`() {
        assertEquals(PendingAction.VERIFY, ClipboardPlan.pendingAction(pending(issued = true), due - 1))
        assertEquals(PendingAction.VERIFY, ClipboardPlan.pendingAction(pending(issued = true), due + ClipboardPlan.VERIFY_MAX_AGE_MILLIS))
        assertEquals(PendingAction.DROP, ClipboardPlan.pendingAction(pending(issued = true), due + ClipboardPlan.VERIFY_MAX_AGE_MILLIS + 1))
    }

    @Test
    fun `a marker without digest has nothing to verify, it is only cleared at the due time`() {
        val blind = PendingClear(CopiedValue.PASSWORD, due, null, issued = false)
        assertEquals(PendingAction.WAIT, ClipboardPlan.pendingAction(blind, due - 1))
        assertEquals(PendingAction.CLEAR, ClipboardPlan.pendingAction(blind, due))
        assertEquals(PendingAction.DROP, ClipboardPlan.pendingAction(blind.copy(issued = true), due))
        assertNull(blind.digest())
    }

    @Test
    fun `the marker lives ten minutes past due`() {
        assertEquals(10 * 60_000L, ClipboardPlan.VERIFY_MAX_AGE_MILLIS)
    }

    @Test
    fun `an unreadable clip is cleared blindly before the first issue and shortly after it only`() {
        assertTrue(ClipboardPlan.mayClearBlind(pending(), due + 5 * 3_600_000L))
        assertTrue(ClipboardPlan.mayClearBlind(pending(issued = true), due + ClipboardPlan.BLIND_WINDOW_MILLIS))
        assertFalse(ClipboardPlan.mayClearBlind(pending(issued = true), due + ClipboardPlan.BLIND_WINDOW_MILLIS + 1))
    }

    @Test
    fun `the backup alarm comes after the countdown and the hex digest round trips`() {
        assertTrue(ClipboardPlan.ALARM_SLACK_MILLIS > 0)
        val bytes = ClipboardGuard.digestOf("Pässwörd-ß 😀")
        assertEquals(64, ClipboardPlan.hex(bytes).length)
        assertContentEquals(bytes, ClipboardPlan.fromHex(ClipboardPlan.hex(bytes)))
        assertContentEquals(bytes, PendingClear(CopiedValue.USERNAME, due, ClipboardPlan.hex(bytes), false).digest())
    }

    @Test
    fun `the marker file round trips, holds no secret and is deleted by null`() {
        val file = File(dir, "keys/$PENDING_CLEAR_FILE")
        val markers = FileClipboardMarkers(file)
        assertNull(markers.read())

        markers.write(pending())
        assertEquals(pending(), markers.read())
        assertFalse(file.readText().contains("geheim-123"), "only the digest is stored")

        markers.write(pending(issued = true))
        assertEquals(pending(issued = true), markers.read())
        assertFalse(File(dir, "keys/$PENDING_CLEAR_FILE.tmp").exists())

        // A marker without digest (the keystore key was unavailable) keeps only the due time.
        markers.write(PendingClear(CopiedValue.USERNAME, due, null, issued = false))
        assertEquals(PendingClear(CopiedValue.USERNAME, due, null, issued = false), markers.read())
        assertFalse(file.readText().contains("digest"))

        markers.write(null)
        assertFalse(file.exists())
        assertNull(markers.read())
        // Deleting what is not there is fine.
        markers.write(null)
    }

    @Test
    fun `a damaged marker file reads as nothing and is removed`() {
        val file = File(dir, PENDING_CLEAR_FILE)
        val markers = FileClipboardMarkers(file)
        file.writeText(listOf("value=PASSWORD", "dueAt=abc", "digest=zz").joinToString(NL))
        assertNull(markers.read())
        assertFalse(file.exists())

        file.writeText(listOf("value=NOPE", "dueAt=5", "digest=00ff").joinToString(NL))
        assertNull(markers.read())
        assertFalse(file.exists())

        // A malformed unicode escape makes the properties parser throw.
        file.writeBytes(byteArrayOf(0x64, 0x3d, 0x5c, 0x75, 0x31))
        assertNull(markers.read())
        assertFalse(file.exists())
    }
}
