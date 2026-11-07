package de.lulurobb.keypassmobile.platform

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * A small documents provider for the device tests: one root, one folder, a few
 * files with and without size and modification time. [denyAccess] makes every
 * query and open throw a SecurityException, like a provider that revoked the
 * app's access. Test values only.
 */
class TestDocumentsProvider : DocumentsProvider() {

    private class Doc(
        val id: String,
        val parent: String?,
        val name: String,
        val mime: String,
        val size: Long?,
        val modified: Long?,
        val bytes: ByteArray = ByteArray(0),
    )

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_FLAGS, 0)
            .add(Root.COLUMN_TITLE, ROOT_TITLE)
            .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC)
            .add(Root.COLUMN_ICON, android.R.drawable.ic_menu_agenda)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        checkAccess()
        val doc = DOCS[documentId] ?: throw FileNotFoundException(documentId)
        return MatrixCursor(projection ?: DOC_COLUMNS).also { addRow(it, doc) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        checkAccess()
        val cursor = MatrixCursor(projection ?: DOC_COLUMNS)
        DOCS.values.filter { it.parent == parentDocumentId }.forEach { addRow(cursor, it) }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var current = DOCS[documentId]?.parent
        while (current != null) {
            if (current == parentDocumentId) return true
            current = DOCS[current]?.parent
        }
        return false
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        checkAccess()
        val doc = DOCS[documentId] ?: throw FileNotFoundException(documentId)
        val file = File.createTempFile("doc", ".bin", context!!.cacheDir)
        file.writeBytes(doc.bytes)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { file.delete() }
    }

    private fun checkAccess() {
        if (denyAccess) throw SecurityException("Access revoked (test)")
    }

    private fun addRow(cursor: MatrixCursor, doc: Doc) {
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, doc.id)
            .add(Document.COLUMN_DISPLAY_NAME, doc.name)
            .add(Document.COLUMN_MIME_TYPE, doc.mime)
            .add(Document.COLUMN_SIZE, doc.size)
            .add(Document.COLUMN_LAST_MODIFIED, doc.modified)
            .add(Document.COLUMN_FLAGS, 0)
    }

    companion object {
        const val AUTHORITY = "de.lulurobb.keypassmobile.platform.test.documents"
        const val ROOT_ID = "kpmtest"
        const val ROOT_TITLE = "Testdokumente"

        /** Document ID of the root folder, of a folder in it and of the files. */
        const val ROOT_DOC = "root"
        const val DIR_DOC = "dir"
        const val FILE_DOC = "file"
        const val NO_SIZE_DOC = "nosize"
        const val NO_MODIFIED_DOC = "nomodified"
        const val MISSING_DOC = "missing"

        const val FILE_NAME = "datenbank.kdbx"
        const val FILE_MODIFIED_MILLIS = 1_793_000_000_000L
        val FILE_BYTES = ByteArray(5_000) { (it % 199).toByte() }

        /** While true, every query and open throws a SecurityException. */
        @Volatile
        var denyAccess = false

        private const val KDBX_MIME = "application/octet-stream"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_ICON,
        )
        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        private val DOCS: Map<String, Doc> = listOf(
            Doc(ROOT_DOC, null, ROOT_TITLE, Document.MIME_TYPE_DIR, null, null),
            Doc(DIR_DOC, ROOT_DOC, "Ordner", Document.MIME_TYPE_DIR, null, null),
            Doc(FILE_DOC, DIR_DOC, FILE_NAME, KDBX_MIME, FILE_BYTES.size.toLong(), FILE_MODIFIED_MILLIS, FILE_BYTES),
            Doc(NO_SIZE_DOC, ROOT_DOC, "ohne-groesse.kdbx", KDBX_MIME, null, FILE_MODIFIED_MILLIS, FILE_BYTES),
            Doc(NO_MODIFIED_DOC, ROOT_DOC, "ohne-datum.kdbx", KDBX_MIME, FILE_BYTES.size.toLong(), null, FILE_BYTES),
        ).associateBy { it.id }
    }
}
