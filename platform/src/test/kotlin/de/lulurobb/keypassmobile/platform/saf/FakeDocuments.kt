package de.lulurobb.keypassmobile.platform.saf

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.Instant

/**
 * In-memory [DocumentAccess] for JVM tests: documents, the set of persisted
 * read permissions, a log of take and release calls, and exceptions that tests
 * inject per uri. Test values only.
 */
internal class FakeDocuments(
    private val internalRoot: String = "content://fake.documents/document/primary%3A",
) : DocumentAccess {

    /** One document. [reportSize] false makes the provider answer without a SIZE column value. */
    class Doc(
        var bytes: ByteArray = ByteArray(0),
        var name: String? = null,
        var modified: Instant? = null,
        var reportSize: Boolean = true,
        var place: String? = null,
        var folder: String? = null,
        /** Thrown while reading the stream (after [readErrorAfter] bytes), to simulate a read error in the middle of the file. */
        var readError: Exception? = null,
        var readErrorAfter: Int = 0,
    )

    val docs = LinkedHashMap<String, Doc>()

    /** URIs with a persisted read permission. */
    val persisted = LinkedHashSet<String>()

    /** Every take and release in order, as "take <uri>" and "release <uri>"; failed takes are logged too. */
    val log = ArrayList<String>()

    /** Thrown by [takeReadPermission] for the uri (typically a SecurityException). */
    val takeErrors = HashMap<String, Exception>()

    /** Thrown by [query] for the uri. */
    val queryErrors = HashMap<String, Exception>()

    /** Thrown by [open] for the uri. */
    val openErrors = HashMap<String, Exception>()

    /** Number of [open] calls that returned a stream or failed, per uri. */
    val opens = HashMap<String, Int>()

    /** Number of [query] calls that returned or failed, per uri. */
    val queries = HashMap<String, Int>()

    fun put(
        uri: String,
        bytes: ByteArray = ByteArray(0),
        name: String? = null,
        modified: Instant? = null,
        reportSize: Boolean = true,
        place: String? = null,
        folder: String? = null,
        readError: Exception? = null,
        readErrorAfter: Int = 0,
    ): Doc = Doc(bytes, name, modified, reportSize, place, folder, readError, readErrorAfter).also { docs[uri] = it }

    override fun takeReadPermission(uri: String) {
        log += "take $uri"
        takeErrors[uri]?.let { throw it }
        persisted += uri
    }

    override fun releaseReadPermission(uri: String) {
        log += "release $uri"
        persisted -= uri
    }

    override fun persistedReadUris(): Set<String> = persisted.toSet()

    override fun query(uri: String): DocumentInfo? {
        queries[uri] = (queries[uri] ?: 0) + 1
        queryErrors[uri]?.let { throw it }
        val doc = docs[uri] ?: return null
        return DocumentInfo(doc.name, if (doc.reportSize) doc.bytes.size.toLong() else null, doc.modified)
    }

    override fun open(uri: String): InputStream {
        opens[uri] = (opens[uri] ?: 0) + 1
        openErrors[uri]?.let { throw it }
        val doc = docs[uri] ?: throw FileNotFoundException(uri)
        val error = doc.readError ?: return ByteArrayInputStream(doc.bytes)
        return FailingStream(doc.bytes, doc.readErrorAfter, error)
    }

    /** Delivers [limit] bytes, then throws [error] on every read. */
    private class FailingStream(bytes: ByteArray, private val limit: Int, private val error: Exception) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        private var delivered = 0

        override fun read(): Int {
            if (delivered >= limit) throw error
            return inner.read().also { if (it >= 0) delivered++ }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (delivered >= limit) throw error
            val n = inner.read(b, off, minOf(len, limit - delivered))
            if (n > 0) delivered += n
            return n
        }
    }

    override fun placeOf(uri: String): String? = docs[uri]?.place

    override fun folderOf(uri: String): String? = docs[uri]?.folder

    override fun internalStorageRoot(): String = internalRoot
}
