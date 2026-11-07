package de.lulurobb.keypassmobile.platform.saf

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.Instant

/**
 * [DocumentAccess] on the ContentResolver of the app (DocumentsContract and
 * OpenableColumns). [internalStorageLabel] is the German name of the internal
 * storage root for the place text ("Interner Speicher", string resource
 * platform_internal_storage).
 */
internal class ContentResolverDocuments(
    private val context: Context,
    private val internalStorageLabel: String,
) : DocumentAccess {

    private val resolver get() = context.contentResolver

    override fun takeReadPermission(uri: String) {
        resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun releaseReadPermission(uri: String) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Nothing to release, or Android refuses: the caller only wants the grant gone.
        }
    }

    override fun persistedReadUris(): Set<String> =
        resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(LinkedHashSet()) { it.uri.toString() }

    override fun query(uri: String): DocumentInfo? {
        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = resolver.query(Uri.parse(uri), projection, null, null, null) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val size = it.longOrNull(OpenableColumns.SIZE)?.takeIf { value -> value >= 0 }
            val modified = it.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                ?.takeIf { value -> value > 0 }
                ?.let { value -> Instant.ofEpochMilli(value) }
            return DocumentInfo(it.stringOrNull(OpenableColumns.DISPLAY_NAME), size, modified)
        }
    }

    override fun open(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException("Document not available")

    override fun placeOf(uri: String): String? {
        val parsed = parse(uri) ?: return null
        return DocumentPlaces.placeText(parsed.authority, parsed.docId, internalStorageLabel) {
            rootTitle(parsed, uri)
        }
    }

    override fun folderOf(uri: String): String? {
        val parsed = parse(uri) ?: return null
        DocumentPlaces.parentDocumentId(parsed.authority, parsed.docId)?.let {
            return DocumentsContract.buildDocumentUri(parsed.authority, it).toString()
        }
        if (DocumentPlaces.isKnownProvider(parsed.authority)) return null
        // Other providers: the path the provider reports, else its only root.
        val path = documentPath(uri)
        val parent = path?.path?.let { DocumentPlaces.parentFromPath(it) }
            ?: queryRoots(parsed.authority).singleOrNull()?.documentId
        return parent?.let { DocumentsContract.buildDocumentUri(parsed.authority, it).toString() }
    }

    override fun internalStorageRoot(): String =
        DocumentsContract.buildDocumentUri(
            DocumentPlaces.EXTERNAL_STORAGE_AUTHORITY,
            DocumentPlaces.PRIMARY_ROOT_DOCUMENT_ID,
        ).toString()

    private class Parsed(val authority: String, val docId: String)

    private class Root(val rootId: String?, val title: String?, val documentId: String?)

    private fun parse(uri: String): Parsed? {
        val parsed = Uri.parse(uri)
        val authority = parsed.authority ?: return null
        val docId = try {
            DocumentsContract.getDocumentId(parsed)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return Parsed(authority, docId)
    }

    /** The path from the root to the document as the provider reports it; null if it cannot. */
    private fun documentPath(uri: String): DocumentsContract.Path? = try {
        DocumentsContract.findDocumentPath(resolver, Uri.parse(uri))
    } catch (_: Exception) {
        null
    }

    /** The roots of the provider. Third-party apps may be refused (MANAGE_DOCUMENTS), then the list is empty. */
    private fun queryRoots(authority: String): List<Root> = try {
        val projection = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        )
        resolver.query(DocumentsContract.buildRootsUri(authority), projection, null, null, null)?.use { cursor ->
            val roots = ArrayList<Root>()
            while (cursor.moveToNext()) {
                roots += Root(
                    cursor.stringOrNull(DocumentsContract.Root.COLUMN_ROOT_ID),
                    cursor.stringOrNull(DocumentsContract.Root.COLUMN_TITLE),
                    cursor.stringOrNull(DocumentsContract.Root.COLUMN_DOCUMENT_ID),
                )
            }
            roots
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** Title of the root the document belongs to; null if the provider does not tell it. */
    private fun rootTitle(parsed: Parsed, uri: String): String? {
        val rootId = if (parsed.authority == DocumentPlaces.EXTERNAL_STORAGE_AUTHORITY) {
            parsed.docId.substringBefore(':', "").ifEmpty { null }
        } else {
            documentPath(uri)?.rootId
        }
        val roots = queryRoots(parsed.authority)
        val root = if (rootId != null) roots.firstOrNull { it.rootId == rootId } else roots.singleOrNull()
        return root?.title
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return try {
            getLong(index)
        } catch (_: NumberFormatException) {
            null
        }
    }
}
