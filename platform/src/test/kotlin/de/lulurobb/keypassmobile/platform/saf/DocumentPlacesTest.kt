package de.lulurobb.keypassmobile.platform.saf

import de.lulurobb.keypassmobile.platform.saf.DocumentPlaces.DOWNLOADS_AUTHORITY
import de.lulurobb.keypassmobile.platform.saf.DocumentPlaces.EXTERNAL_STORAGE_AUTHORITY
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentPlacesTest {

    private val label = "Interner Speicher"

    private fun place(authority: String, docId: String, title: String? = null): String? =
        DocumentPlaces.placeText(authority, docId, label) { title }

    @Test
    fun `internal storage gives the label and the folders without the file name`() {
        assertEquals(
            "Interner Speicher / Syncthing / KeePass",
            place(EXTERNAL_STORAGE_AUTHORITY, "primary:Syncthing/KeePass/Passwoerter.kdbx"),
        )
        assertEquals("Interner Speicher / Download", place(EXTERNAL_STORAGE_AUTHORITY, "primary:Download/Passwoerter.kdbx"))
    }

    @Test
    fun `a file in the root of the internal storage gives just the label`() {
        assertEquals("Interner Speicher", place(EXTERNAL_STORAGE_AUTHORITY, "primary:Passwoerter.kdbx"))
    }

    @Test
    fun `folder names keep umlauts and spaces`() {
        assertEquals(
            "Interner Speicher / Ärzte / Mein Ordner",
            place(EXTERNAL_STORAGE_AUTHORITY, "primary:Ärzte/Mein Ordner/neu.kdbx"),
        )
    }

    @Test
    fun `the root title is not looked up for the internal storage`() {
        var asked = false
        DocumentPlaces.placeText(EXTERNAL_STORAGE_AUTHORITY, "primary:A/f.kdbx", label) {
            asked = true
            "x"
        }
        assertFalse(asked)
    }

    @Test
    fun `an SD card gives its root title and the folders`() {
        assertEquals(
            "SD-Karte / Backup / KeePass",
            place(EXTERNAL_STORAGE_AUTHORITY, "1234-ABCD:Backup/KeePass/db.kdbx", "SD-Karte"),
        )
        assertEquals("SD-Karte", place(EXTERNAL_STORAGE_AUTHORITY, "1234-ABCD:db.kdbx", "SD-Karte"))
    }

    @Test
    fun `an SD card without a known root title gives no place`() {
        assertNull(place(EXTERNAL_STORAGE_AUTHORITY, "1234-ABCD:Backup/db.kdbx", null))
        assertNull(place(EXTERNAL_STORAGE_AUTHORITY, "1234-ABCD:Backup/db.kdbx", " "))
    }

    @Test
    fun `a malformed external storage id gives no place`() {
        assertNull(place(EXTERNAL_STORAGE_AUTHORITY, "kaputt", null))
    }

    @Test
    fun `downloads gives the root title or Downloads`() {
        assertEquals("Downloads", place(DOWNLOADS_AUTHORITY, "msf:1234"))
        assertEquals("Downloads", place(DOWNLOADS_AUTHORITY, "raw:/storage/emulated/0/Download/db.kdbx"))
        assertEquals("Heruntergeladen", place(DOWNLOADS_AUTHORITY, "msf:1234", "Heruntergeladen"))
    }

    @Test
    fun `another provider gives its root title or null`() {
        assertEquals("Dokumente", place("com.example.documents", "doc42", "Dokumente"))
        assertNull(place("com.example.documents", "doc42", null))
        assertNull(place("com.example.documents", "doc42", ""))
    }

    @Test
    fun `the parent of an internal storage file is the folder`() {
        assertEquals("primary:A/B", DocumentPlaces.parentDocumentId(EXTERNAL_STORAGE_AUTHORITY, "primary:A/B/f.kdbx"))
        assertEquals("primary:A", DocumentPlaces.parentDocumentId(EXTERNAL_STORAGE_AUTHORITY, "primary:A/B"))
    }

    @Test
    fun `the parent of a file in the root is the root`() {
        assertEquals("primary:", DocumentPlaces.parentDocumentId(EXTERNAL_STORAGE_AUTHORITY, "primary:f.kdbx"))
        assertEquals(
            DocumentPlaces.PRIMARY_ROOT_DOCUMENT_ID,
            DocumentPlaces.parentDocumentId(EXTERNAL_STORAGE_AUTHORITY, "primary:f.kdbx"),
        )
    }

    @Test
    fun `a root has no parent`() {
        assertNull(DocumentPlaces.parentDocumentId(EXTERNAL_STORAGE_AUTHORITY, "primary:"))
        assertNull(DocumentPlaces.parentDocumentId(EXTERNAL_STORAGE_AUTHORITY, "1234-ABCD:"))
        assertNull(DocumentPlaces.parentDocumentId(EXTERNAL_STORAGE_AUTHORITY, "kaputt"))
        assertNull(DocumentPlaces.parentDocumentId(DOWNLOADS_AUTHORITY, "downloads"))
    }

    @Test
    fun `the parent of an SD card file keeps its root`() {
        assertEquals("1234-ABCD:Backup", DocumentPlaces.parentDocumentId(EXTERNAL_STORAGE_AUTHORITY, "1234-ABCD:Backup/db.kdbx"))
        assertEquals("1234-ABCD:", DocumentPlaces.parentDocumentId(EXTERNAL_STORAGE_AUTHORITY, "1234-ABCD:db.kdbx"))
    }

    @Test
    fun `the parent of a raw download is the parent path`() {
        assertEquals(
            "raw:/storage/emulated/0/Download",
            DocumentPlaces.parentDocumentId(DOWNLOADS_AUTHORITY, "raw:/storage/emulated/0/Download/db.kdbx"),
        )
    }

    @Test
    fun `the parent of any other download is the downloads root`() {
        assertEquals("downloads", DocumentPlaces.parentDocumentId(DOWNLOADS_AUTHORITY, "msf:1234"))
        assertEquals("downloads", DocumentPlaces.parentDocumentId(DOWNLOADS_AUTHORITY, "1234"))
        assertEquals("downloads", DocumentPlaces.parentDocumentId(DOWNLOADS_AUTHORITY, "raw:/db.kdbx"))
    }

    @Test
    fun `another provider has no rule of its own`() {
        assertNull(DocumentPlaces.parentDocumentId("com.example.documents", "doc42"))
        assertFalse(DocumentPlaces.isKnownProvider("com.example.documents"))
        assertTrue(DocumentPlaces.isKnownProvider(EXTERNAL_STORAGE_AUTHORITY))
        assertTrue(DocumentPlaces.isKnownProvider(DOWNLOADS_AUTHORITY))
    }

    @Test
    fun `the parent from a provider path is the second to last element`() {
        assertEquals("dir", DocumentPlaces.parentFromPath(listOf("root", "dir", "file")))
        assertEquals("root", DocumentPlaces.parentFromPath(listOf("root", "file")))
        assertNull(DocumentPlaces.parentFromPath(listOf("file")))
        assertNull(DocumentPlaces.parentFromPath(emptyList()))
    }
}
