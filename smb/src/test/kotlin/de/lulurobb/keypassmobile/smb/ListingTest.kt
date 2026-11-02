package de.lulurobb.keypassmobile.smb

import de.lulurobb.keypassmobile.smb.rpc.RawShare
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Path normalization, folder filtering and German sorting, share filtering. */
class ListingTest {

    private val t = Instant.parse("2026-10-01T12:00:00Z")
    private fun dir(name: String) = RawEntry(name, 0x10, 0, t)
    private fun file(name: String, size: Long = 10) = RawEntry(name, 0x20, size, t)

    @Test
    fun normalizePathHandlesSeparators() {
        assertEquals("", normalizePath(""))
        assertEquals("", normalizePath("/"))
        assertEquals("", normalizePath("\\\\"))
        assertEquals("a\\b\\c.kdbx", normalizePath("/a/b/c.kdbx"))
        assertEquals("a\\b", normalizePath("a\\\\b\\"))
        assertEquals("a\\b", normalizePath("./a/./b"))
        assertEquals("Ärzte\\Praxis Müller", normalizePath("Ärzte/Praxis Müller/"))
    }

    @Test
    fun folderItemsKeepFoldersAndKdbxOnlyAndSortGerman() {
        val entries = listOf(
            dir("."), dir(".."),
            dir("Zeitung"), dir("Ärzte"), dir("apfel"), dir("Übersicht"), dir("Bank"), dir("Straße"),
            file("test.kdbx", 2048), file("Österreich.kdbx"), file("Archiv.KDBX"), file("alt.kdbx"),
            file("notizen.txt"), file("test.kdbx.bak"), file("kdbx"), file("Ölfass.Kdbx"),
            // A folder whose name ends in .kdbx is still a folder.
            dir("ordner.kdbx"),
        )
        val items = toFolderItems(entries)
        assertEquals(
            listOf(
                "apfel", "Ärzte", "Bank", "ordner.kdbx", "Straße", "Übersicht", "Zeitung",
                "alt.kdbx", "Archiv.KDBX", "Ölfass.Kdbx", "Österreich.kdbx", "test.kdbx",
            ),
            items.map { it.name },
        )
        assertEquals(7, items.count { it.isFolder })
        assertEquals(0L, items.first { it.name == "Bank" }.size)
        assertEquals(2048L, items.first { it.name == "test.kdbx" }.size)
        assertEquals(t, items.first().modified)
    }

    @Test
    fun otherFilesAreCountedButFoldersAndKdbxAreNot() {
        val listing = toFolderListing(
            listOf(
                dir("."), dir(".."), dir("Ordner"), dir("ordner.txt"),
                file("a.kdbx"), file("B.KDBX"),
                file("notizen.txt"), file("test.kdbx.bak"), file("kdbx"), file(".DS_Store"), file("Thumbs.db"),
            ),
        )
        assertEquals(listOf("Ordner", "ordner.txt", "a.kdbx", "B.KDBX"), listing.items.map { it.name })
        // Only files: "." and ".." and folders are no files.
        assertEquals(5, listing.otherFileCount)
        assertEquals(0, toFolderListing(emptyList()).otherFileCount)
        assertEquals(0, toFolderListing(listOf(dir("x"), file("y.kdbx"))).otherFileCount)
    }

    @Test
    fun germanOrderIsStableForNamesDifferingOnlyInCase() {
        val names = listOf("b", "B", "a", "A", "ä", "Ä")
        val sorted = names.sortedWith(germanNameOrder())
        assertEquals(sorted, names.reversed().sortedWith(germanNameOrder()))
        assertEquals(listOf("a", "A", "ä", "Ä", "b", "B"), sorted)
    }

    @Test
    fun diskSharesWithoutSpecialAndDollarShares() {
        val raw = listOf(
            RawShare("kpm", 0x0, "KeePass"),
            RawShare("IPC$", 0x80000003L, "IPC Service"),
            RawShare("ADMIN$", 0x80000000L, "Remote Admin"),
            RawShare("C$", 0x80000000L, "Default share"),
            RawShare("print$", 0x0, "Printer Drivers"),
            RawShare("versteckt$", 0x0, ""),
            RawShare("Drucker", 0x1, "Laser"),
            RawShare("COM1", 0x2, ""),
            RawShare("Büro", 0x0, ""),
            RawShare("Cluster", 0x02000000L, "cluster disk share"),
            RawShare("Temp", 0x40000000L, "temporary disk share"),
            RawShare("", 0x0, "no name"),
            RawShare("Archiv", 0x0, "Alt"),
        )
        assertEquals(
            listOf(
                ShareInfo("Archiv", "Alt"),
                ShareInfo("Büro", ""),
                ShareInfo("Cluster", "cluster disk share"),
                ShareInfo("kpm", "KeePass"),
                ShareInfo("Temp", "temporary disk share"),
            ),
            toDiskShares(raw),
        )
    }
}
