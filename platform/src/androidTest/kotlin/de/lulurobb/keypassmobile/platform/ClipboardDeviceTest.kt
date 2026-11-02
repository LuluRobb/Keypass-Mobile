package de.lulurobb.keypassmobile.platform

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.lulurobb.keypassmobile.core.settings.ClipboardTimeout
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardClearReceiver
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardGuard
import de.lulurobb.keypassmobile.platform.clipboard.FileClipboardMarkers
import de.lulurobb.keypassmobile.platform.clipboard.KeystoreClipboardMac
import de.lulurobb.keypassmobile.platform.clipboard.PENDING_CLEAR_FILE
import de.lulurobb.keypassmobile.platform.clipboard.SystemClipboardAlarms
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardStatus
import de.lulurobb.keypassmobile.platform.clipboard.CopiedValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The test process has no focused window, like the app while the user pastes
 * elsewhere. Reading back needs the shell's background clipboard permission.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardDeviceTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    private val markerFile = File(context.cacheDir, "pending-clear-test/$PENDING_CLEAR_FILE")
    private val markers = FileClipboardMarkers(markerFile)
    private val alarms = RecordingAlarms()

    private fun guardWithMarker() =
        ClipboardGuard(context, scope, SystemClock::elapsedRealtime, markers = markers, alarms = alarms)

    @After
    fun cleanUp() {
        scope.cancel()
        automation.dropShellPermissionIdentity()
        markerFile.parentFile?.deleteRecursively()
    }

    private fun clipIsEmpty(): Boolean {
        val clip = readClip()
        return clip == null || clip.itemCount == 0 || clip.getItemAt(0).text.isNullOrEmpty()
    }

    private fun readClip(): ClipData? {
        automation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
        try {
            return manager.primaryClip
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun copiesSensitiveAndClearsInTheBackground() {
        val guard = ClipboardGuard(context, scope, SystemClock::elapsedRealtime)
        assertTrue(guard.copy(CopiedValue.PASSWORD, "geheim-123", ClipboardTimeout.SECONDS_15))

        val copied = readClip()
        assertEquals("geheim-123", copied?.getItemAt(0)?.text?.toString())
        // The emulator replaces clip extras with its own overlay flag when reading back,
        // so the sensitive flag is checked on the clip the guard hands to the system.
        val built = ClipboardGuard.sensitiveClip("KeyPass Mobile", "x")
        assertTrue(built.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))

        guard.clearNow()
        assertEquals(ClipboardStatus.Cleared(CopiedValue.PASSWORD), guard.status.value)
        val after = readClip()
        assertTrue("clip still present: ${after?.getItemAt(0)?.text}", after == null || after.itemCount == 0 || after.getItemAt(0).text.isNullOrEmpty())
    }

    @Test
    fun staleCountdownNeverTouchesANewerCopyOrACleared() {
        val guard = ClipboardGuard(context, scope, SystemClock::elapsedRealtime)
        // A second copy of the same value replaces the first: the status stays Holding with a fresh countdown.
        assertTrue(guard.copy(CopiedValue.USERNAME, "erst", ClipboardTimeout.SECONDS_15))
        assertTrue(guard.copy(CopiedValue.USERNAME, "zweit", ClipboardTimeout.SECONDS_15))
        Thread.sleep(1_500)
        val holding = guard.status.value
        assertTrue("$holding", holding is ClipboardStatus.Holding && holding.secondsLeft in 12..14)

        // After a clear, no tick of the old countdown writes Holding again.
        guard.clearNow()
        Thread.sleep(2_500)
        assertEquals(ClipboardStatus.Cleared(CopiedValue.USERNAME), guard.status.value)
    }

    @Test
    fun copyIsRefusedInsideTheLockWhenNotAllowed() {
        val guard = ClipboardGuard(context, scope, SystemClock::elapsedRealtime)
        // The session locked between the caller's check and the copy: nothing may be written.
        assertEquals(false, guard.copy(CopiedValue.PASSWORD, "geheim", ClipboardTimeout.SECONDS_15) { false })
        assertEquals(ClipboardStatus.Idle, guard.status.value)
        val clip = readClip()
        assertTrue(clip == null || clip.itemCount == 0 || clip.getItemAt(0).text?.toString() != "geheim")
    }

    @Test
    fun aClearThatCannotBeVerifiedIsRememberedAndTheAlarmCancelled() {
        val guard = guardWithMarker()
        assertTrue(guard.copy(CopiedValue.PASSWORD, "geheim-b1", ClipboardTimeout.SECONDS_15))
        assertEquals(false, markers.read()?.issued)
        assertTrue(alarms.scheduled.isNotEmpty())
        val cancels = alarms.cancels

        guard.clearNow()

        // Without focus the clip cannot be read: the clear is issued, Cleared says so, and the
        // marker stays (issued) so that the app looks again when it is in front.
        assertEquals(ClipboardStatus.Cleared(CopiedValue.PASSWORD), guard.status.value)
        assertEquals(true, markers.read()?.issued)
        assertTrue(alarms.cancels > cancels)
        assertTrue("clip still present", clipIsEmpty())
    }

    @Test
    fun anOverdueMarkerIsClearedWithoutFocus() {
        markers.write(pendingFor("geheim-b2", dueInMillis = -5_000, issued = false))
        manager.setPrimaryClip(ClipData.newPlainText("Test", "geheim-b2"))
        assertEquals("geheim-b2", readClip()?.getItemAt(0)?.text?.toString())
        val guard = guardWithMarker()

        guard.checkPending()

        assertEquals(ClipboardStatus.Cleared(CopiedValue.PASSWORD), guard.status.value)
        assertTrue("clip still present", clipIsEmpty())
        assertEquals(true, markers.read()?.issued)
    }

    @Test
    fun theIssuedClearIsRepeatedAfterTwoSeconds() {
        val guard = guardWithMarker()
        assertTrue(guard.copy(CopiedValue.USERNAME, "kpmtest-b3", ClipboardTimeout.SECONDS_15))
        guard.clearNow()
        assertEquals(ClipboardStatus.Cleared(CopiedValue.USERNAME), guard.status.value)
        // Something puts the value back right after the clear.
        manager.setPrimaryClip(ClipData.newPlainText("Tastatur", "kpmtest-b3"))

        // The second clear is blind (no focus) and removes it again. (The emulator's host clipboard sync may
        // overwrite the clip meanwhile, so this cannot assert that the value was there in between.)
        assertTrue("clip still present", waitUntil(6_000) { clipIsEmpty() })
    }

    @Test
    fun theBackupAlarmIsAPendingIntentToTheReceiverAndCanBeCancelled() {
        val alarm = SystemClipboardAlarms(context)
        fun existing() = PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, ClipboardClearReceiver::class.java).setAction(ClipboardClearReceiver.ACTION_CLEAR),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        assertNull(existing())

        alarm.schedule(System.currentTimeMillis() + 3_600_000)
        assertNotNull(existing())

        alarm.cancel()
        assertNull(existing())
    }

    @Test
    fun theReceiverClearsAnOverdueMarkerThroughThePlatform() {
        // The facade of the process owns the marker file; the alarm only wakes it.
        KpmPlatform.init(context)
        val file = File(context.noBackupFilesDir, "keys/$PENDING_CLEAR_FILE")
        val real = FileClipboardMarkers(file)
        try {
            // The facade's guard recognizes values by its keystore HMAC, so the marker is made with the same key.
            real.write(pendingFor("geheim-b4", dueInMillis = -5_000, issued = false, mac = KeystoreClipboardMac()))
            manager.setPrimaryClip(ClipData.newPlainText("Test", "geheim-b4"))
            assertEquals("geheim-b4", readClip()?.getItemAt(0)?.text?.toString())

            context.sendBroadcast(
                Intent(context, ClipboardClearReceiver::class.java).setAction(ClipboardClearReceiver.ACTION_CLEAR),
            )

            assertTrue("clip still present", waitUntil(10_000) { clipIsEmpty() })
            assertTrue("marker issued or dropped", waitUntil { real.read()?.issued != false })
        } finally {
            real.write(null)
        }
    }

    // Keeping foreign content is covered by the JVM test of ClipboardGuard.shouldClear:
    // on the emulator the host clipboard sync restores older clips, which makes it flaky here.
}
