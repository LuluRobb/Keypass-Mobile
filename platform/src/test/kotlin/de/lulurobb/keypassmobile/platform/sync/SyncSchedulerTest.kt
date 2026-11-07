package de.lulurobb.keypassmobile.platform.sync

import androidx.work.NetworkType
import de.lulurobb.keypassmobile.platform.settings.FileSource
import de.lulurobb.keypassmobile.platform.settings.SmbSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The constraint of the periodic sync follows the source type (SY-06). */
class SyncSchedulerTest {

    private val smb = SmbSource("nas", 445, "", "kpm", "daten", listOf("db.kdbx"))
    private val file = FileSource("content://docs/document/primary%3ADb.kdbx", "Db.kdbx", null)

    @Test
    fun `an SMB source needs a network, a file on the phone does not`() {
        assertTrue(needsNetwork(smb))
        assertFalse(needsNetwork(file))
        // No source yet: the SMB rule, the safe default.
        assertTrue(needsNetwork(null))
    }

    @Test
    fun `the constraint is connected for SMB and none for a file`() {
        assertEquals(NetworkType.CONNECTED, syncConstraints(needsNetwork(smb)).requiredNetworkType)
        assertEquals(NetworkType.NOT_REQUIRED, syncConstraints(needsNetwork(file)).requiredNetworkType)
    }
}
