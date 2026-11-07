package de.lulurobb.keypassmobile.platform

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.lulurobb.keypassmobile.core.sync.RemoteError
import de.lulurobb.keypassmobile.core.sync.RemoteException
import de.lulurobb.keypassmobile.core.sync.RemoteStat
import de.lulurobb.keypassmobile.platform.TestDocumentsProvider.Companion.AUTHORITY
import de.lulurobb.keypassmobile.platform.saf.ContentResolverDocuments
import de.lulurobb.keypassmobile.platform.saf.DocumentAccess
import de.lulurobb.keypassmobile.platform.saf.SafRemoteSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant

/**
 * [ContentResolverDocuments] and [SafRemoteSource] against a real
 * ContentResolver and a documents provider of the test APK. Persistable grants
 * cannot be tested with a provider of the own package (the app needs none for
 * it); the emulator E2E covers them.
 */
@RunWith(AndroidJUnit4::class)
class SafDocumentsDeviceTest {

    private val documents = ContentResolverDocuments(context, "Interner Speicher")

    @After
    fun reset() {
        TestDocumentsProvider.denyAccess = false
    }

    private fun uri(documentId: String): String = DocumentsContract.buildDocumentUri(AUTHORITY, documentId).toString()

    /** The real documents, but with a persisted read permission for [uri] so [SafRemoteSource] gets to the provider. */
    private fun withGrant(uri: String): DocumentAccess = object : DocumentAccess by documents {
        override fun persistedReadUris(): Set<String> = setOf(uri)
    }

    @Test
    fun queryReadsNameSizeAndModificationTime() {
        val info = documents.query(uri(TestDocumentsProvider.FILE_DOC))!!
        assertEquals(TestDocumentsProvider.FILE_NAME, info.displayName)
        assertEquals(TestDocumentsProvider.FILE_BYTES.size.toLong(), info.sizeBytes)
        assertEquals(Instant.ofEpochMilli(TestDocumentsProvider.FILE_MODIFIED_MILLIS), info.lastModified)
    }

    @Test
    fun queryHandlesNullColumnValues() {
        val noSize = documents.query(uri(TestDocumentsProvider.NO_SIZE_DOC))!!
        assertNull(noSize.sizeBytes)
        assertEquals(Instant.ofEpochMilli(TestDocumentsProvider.FILE_MODIFIED_MILLIS), noSize.lastModified)

        val noModified = documents.query(uri(TestDocumentsProvider.NO_MODIFIED_DOC))!!
        assertNull(noModified.lastModified)
        assertEquals(TestDocumentsProvider.FILE_BYTES.size.toLong(), noModified.sizeBytes)
    }

    @Test
    fun queryOfAMissingDocumentIsNull() {
        assertNull(documents.query(uri(TestDocumentsProvider.MISSING_DOC)))
    }

    @Test
    fun openReturnsTheContent() {
        val bytes = documents.open(uri(TestDocumentsProvider.FILE_DOC)).use { it.readBytes() }
        assertArrayEquals(TestDocumentsProvider.FILE_BYTES, bytes)
    }

    @Test
    fun openOfAMissingDocumentIsFileNotFound() {
        assertThrows(FileNotFoundException::class.java) { documents.open(uri(TestDocumentsProvider.MISSING_DOC)) }
    }

    @Test
    fun deniedAccessThrowsSecurityException() {
        TestDocumentsProvider.denyAccess = true
        assertThrows(SecurityException::class.java) { documents.query(uri(TestDocumentsProvider.FILE_DOC)) }
        assertThrows(SecurityException::class.java) { documents.open(uri(TestDocumentsProvider.FILE_DOC)) }
    }

    @Test
    fun remoteSourceReadsStatAndDownload() {
        val uri = uri(TestDocumentsProvider.FILE_DOC)
        val source = SafRemoteSource(withGrant(uri), uri)
        assertEquals(
            RemoteStat(TestDocumentsProvider.FILE_BYTES.size.toLong(), Instant.ofEpochMilli(TestDocumentsProvider.FILE_MODIFIED_MILLIS)),
            source.stat(),
        )
        val target = File(context.cacheDir, "saf-download.bin")
        try {
            source.download(target)
            assertArrayEquals(TestDocumentsProvider.FILE_BYTES, target.readBytes())
        } finally {
            target.delete()
        }
    }

    @Test
    fun remoteSourceCountsAnUnknownSizeAndMarksAnUnknownTime() {
        val noSize = uri(TestDocumentsProvider.NO_SIZE_DOC)
        assertEquals(TestDocumentsProvider.FILE_BYTES.size.toLong(), SafRemoteSource(withGrant(noSize), noSize).stat().size)

        val noModified = uri(TestDocumentsProvider.NO_MODIFIED_DOC)
        assertEquals(RemoteStat.UNKNOWN_MODIFIED, SafRemoteSource(withGrant(noModified), noModified).stat().modified)
    }

    @Test
    fun remoteSourceMapsMissingAndDeniedAndNoGrant() {
        val missing = uri(TestDocumentsProvider.MISSING_DOC)
        assertEquals(RemoteError.NOT_FOUND, errorOf { SafRemoteSource(withGrant(missing), missing).stat() })

        val file = uri(TestDocumentsProvider.FILE_DOC)
        TestDocumentsProvider.denyAccess = true
        assertEquals(RemoteError.ACCESS_DENIED, errorOf { SafRemoteSource(withGrant(file), file).stat() })
        TestDocumentsProvider.denyAccess = false

        // The app holds no persisted grant for the own-package provider.
        assertFalse(file in documents.persistedReadUris())
        assertEquals(RemoteError.ACCESS_DENIED, errorOf { SafRemoteSource(documents, file).stat() })
    }

    @Test
    fun releasingAnUnknownGrantDoesNotThrow() {
        documents.releaseReadPermission(uri(TestDocumentsProvider.FILE_DOC))
    }

    @Test
    fun internalStorageRootIsThePrimaryRoot() {
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3A",
            documents.internalStorageRoot(),
        )
    }

    @Test
    fun placeAndFolderOfInternalStorageFiles() {
        val file = "content://com.android.externalstorage.documents/document/primary%3ASyncthing%2FKeePass%2Fdb.kdbx"
        assertEquals("Interner Speicher / Syncthing / KeePass", documents.placeOf(file))
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3ASyncthing%2FKeePass",
            documents.folderOf(file),
        )

        val inRoot = "content://com.android.externalstorage.documents/document/primary%3Adb.kdbx"
        assertEquals("Interner Speicher", documents.placeOf(inRoot))
        assertEquals(documents.internalStorageRoot(), documents.folderOf(inRoot))
    }

    @Test
    fun folderOfARawDownloadIsItsParentPath() {
        val raw = "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fdb.kdbx"
        assertEquals(
            "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload",
            documents.folderOf(raw),
        )
        val msf = "content://com.android.providers.downloads.documents/document/msf%3A1234"
        assertEquals("content://com.android.providers.downloads.documents/document/downloads", documents.folderOf(msf))
        assertNotNull(documents.placeOf(msf))
    }

    @Test
    fun anUnparsableUriGivesNoPlaceAndNoFolder() {
        assertNull(documents.placeOf("keine-uri"))
        assertNull(documents.folderOf("keine-uri"))
    }

    @Test
    fun otherProviderUsesItsOnlyRoot() {
        // Third-party apps may not ask a provider for the document path (MANAGE_DOCUMENTS), so the
        // rules fall back to the only root of the provider: its title and its root document.
        val file = uri(TestDocumentsProvider.FILE_DOC)
        assertEquals(TestDocumentsProvider.ROOT_TITLE, documents.placeOf(file))
        assertEquals(uri(TestDocumentsProvider.ROOT_DOC), documents.folderOf(file))
    }

    private fun errorOf(block: () -> Unit): RemoteError =
        assertThrows(RemoteException::class.java) { block() }.error
}
