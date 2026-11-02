package de.lulurobb.keypassmobile.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.lulurobb.keypassmobile.core.settings.ClipboardTimeout
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardGuard
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardStatus
import de.lulurobb.keypassmobile.platform.clipboard.CopiedValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The test process has no focused window, like the app while the user pastes
 * elsewhere. Reading back needs the shell's background clipboard permission.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardDeviceTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    @After
    fun cleanUp() {
        scope.cancel()
        automation.dropShellPermissionIdentity()
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

    // Keeping foreign content is covered by the JVM test of ClipboardGuard.shouldClear:
    // on the emulator the host clipboard sync restores older clips, which makes it flaky here.
}
