package de.lulurobb.keypassmobile.platform.session

import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwitchJournalTest {

    @TempDir
    lateinit var dir: File

    private val target = SourceConfig("nas", 4450, "WORK", "kpm", "daten", listOf("Ärzte", "neu.kdbx"))

    @Test
    fun `a record survives a restart and deleting it leaves nothing`() {
        val file = File(dir, "keys/switch.properties")
        val record = SwitchRecord(target, differs = true, setAside = true, offlineReplaced = false, passwordTouched = true)
        assertNull(FileSwitchJournal(file).read())
        FileSwitchJournal(file).write(record)
        assertEquals(record, FileSwitchJournal(file).read())

        val changed = record.copy(setAside = false, offlineReplaced = true, passwordTouched = false)
        FileSwitchJournal(file).write(changed)
        assertEquals(changed, FileSwitchJournal(file).read())

        FileSwitchJournal(file).write(null)
        assertFalse(file.exists())
        assertNull(FileSwitchJournal(file).read())
        // Deleting twice is fine.
        FileSwitchJournal(file).write(null)
    }

    @Test
    fun `an unreadable record still means a change was running and is undone completely`() {
        val file = File(dir, "switch.properties")
        file.writeBytes(byteArrayOf(0x5C, 0x75, 0x00, 0x01))
        val record = FileSwitchJournal(file).read()!!
        assertNull(record.target)
        assertTrue(record.differs && record.setAside && record.passwordTouched)
    }
}
