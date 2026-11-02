package de.lulurobb.keypassmobile.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.lulurobb.keypassmobile.core.settings.ClipboardTimeout
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardGuard
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardMac
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardPlan
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardStatus
import de.lulurobb.keypassmobile.platform.clipboard.CopiedValue
import de.lulurobb.keypassmobile.platform.clipboard.FileClipboardMarkers
import de.lulurobb.keypassmobile.platform.clipboard.KeystoreClipboardMac
import de.lulurobb.keypassmobile.platform.clipboard.PENDING_CLEAR_FILE
import de.lulurobb.keypassmobile.platform.keystore.KeySpecs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The keyed digest of the clipboard marker with a real AndroidKeyStore key (a test alias, not the app's). */
@RunWith(AndroidJUnit4::class)
class ClipboardMacDeviceTest {

    private val alias = "test_clipboard_marker"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val markerFile = File(context.cacheDir, "pending-clear-mac-test/$PENDING_CLEAR_FILE")
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val manager = context.getSystemService(ClipboardManager::class.java)

    @After
    fun cleanUp() {
        scope.cancel()
        automation.dropShellPermissionIdentity()
        KeySpecs.deleteKeyQuietly(alias)
        markerFile.parentFile?.deleteRecursively()
    }

    @Test
    fun theKeyedDigestIsStableKeyedAndNotAPlainHash() {
        val mac = KeystoreClipboardMac(alias)
        val first = mac.mac("geheim-k1")!!
        assertEquals(32, first.size)
        assertArrayEquals(first, mac.mac("geheim-k1"))
        assertArrayEquals("a new instance finds the same key", first, KeystoreClipboardMac(alias).mac("geheim-k1"))
        assertFalse(first.contentEquals(mac.mac("geheim-k2")))
        assertFalse("not the plain SHA-256", first.contentEquals(ClipboardGuard.digestOf("geheim-k1")))
        // The key is in the keystore and cannot be exported.
        assertTrue(KeySpecs.keyStore().containsAlias(alias))
        assertNull(KeySpecs.keyStore().getKey(alias, null).encoded)
        // A deleted key is created again; markers of the old key no longer match.
        KeySpecs.deleteKeyQuietly(alias)
        assertFalse(first.contentEquals(KeystoreClipboardMac(alias).mac("geheim-k1")))
    }

    @Test
    fun theMarkerFileHoldsTheKeyedDigestNeverTheTextOrAPlainHash() {
        val markers = FileClipboardMarkers(markerFile)
        val guard = ClipboardGuard(context, scope, SystemClock::elapsedRealtime, mac = KeystoreClipboardMac(alias), markers = markers)
        assertTrue(guard.copy(CopiedValue.PASSWORD, "geheim-k3", ClipboardTimeout.SECONDS_15))

        val text = markerFile.readText()
        val stored = markers.read()!!.digestHex
        assertNotNull(stored)
        assertFalse(text.contains("geheim-k3"))
        assertFalse("no plain hash", text.contains(ClipboardPlan.hex(ClipboardGuard.digestOf("geheim-k3"))))
        assertEquals(ClipboardPlan.hex(KeystoreClipboardMac(alias).mac("geheim-k3")!!), stored)
        guard.clearNow()
    }

    @Test
    fun withoutAKeyNothingIsPersistedThatCouldBeVerifiedAndTheClipIsClearedBlindly() {
        val noKey = object : ClipboardMac {
            override fun mac(text: CharSequence): ByteArray? = null
        }
        val markers = FileClipboardMarkers(markerFile)
        val guard = ClipboardGuard(context, scope, SystemClock::elapsedRealtime, mac = noKey, markers = markers)
        assertTrue(guard.copy(CopiedValue.PASSWORD, "geheim-k4", ClipboardTimeout.SECONDS_15))
        // Due time only, no digest of any kind.
        val held = markers.read()!!
        assertNull(held.digestHex)
        assertFalse(markerFile.readText().contains("digest"))

        // At the due time (or lock) the clip is cleared; with no digest to verify against, the marker is gone afterwards.
        guard.clearNow()
        assertTrue("status ${guard.status.value}", waitUntil { guard.status.value is ClipboardStatus.Cleared })
        assertNull(markers.read())
        automation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
        val clip: ClipData? = manager.primaryClip
        assertTrue(clip == null || clip.itemCount == 0 || clip.getItemAt(0).text.isNullOrEmpty())
    }
}
