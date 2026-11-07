package de.lulurobb.keypassmobile.platform.session

import de.lulurobb.keypassmobile.platform.settings.FileSource
import de.lulurobb.keypassmobile.platform.settings.SmbSource
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

    private val target = SmbSource("nas", 4450, "WORK", "kpm", "daten", listOf("Ärzte", "neu.kdbx"))

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
    fun `a file target survives a restart with and without a place`() {
        val file = File(dir, "switch.properties")
        val withPlace = FileSource("content://com.example.docs/document/primary%3ASync%2FPw.kdbx", "Pw.kdbx", "Interner Speicher / Sync")
        val record = SwitchRecord(withPlace, differs = true, setAside = false, offlineReplaced = true, passwordTouched = false)
        FileSwitchJournal(file).write(record)
        assertEquals(record, FileSwitchJournal(file).read())

        val withoutPlace = record.copy(target = FileSource("content://com.example.docs/document/42", "Pw.kdbx", null))
        FileSwitchJournal(file).write(withoutPlace)
        assertEquals(withoutPlace, FileSwitchJournal(file).read())
    }

    @Test
    fun `an old record without a type reads as an SMB target`() {
        val file = File(dir, "switch.properties")
        // As written before the file source existed: no type key.
        file.writeText(
            "host=nas\nport=4450\ndomain=WORK\nuser=kpm\nshare=daten\npath=old.kdbx\n" +
                "differs=true\nsetAside=true\nofflineReplaced=false\npasswordTouched=true\n",
        )
        val record = FileSwitchJournal(file).read()!!
        assertEquals(SmbSource("nas", 4450, "WORK", "kpm", "daten", listOf("old.kdbx")), record.target)
        assertTrue(record.differs && record.setAside && record.passwordTouched)
        assertFalse(record.offlineReplaced)
    }

    @Test
    fun `an unknown target type reads as no target but still counts as a running change`() {
        val file = File(dir, "switch.properties")
        file.writeText("type=ftp\nhost=nas\nuser=kpm\nshare=daten\npath=old.kdbx\ndiffers=true\n")
        val record = FileSwitchJournal(file).read()!!
        assertNull(record.target)
        assertTrue(record.differs)
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
