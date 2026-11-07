package de.lulurobb.keypassmobile.platform.saf

import de.lulurobb.keypassmobile.core.sync.RemoteError
import de.lulurobb.keypassmobile.core.sync.RemoteException
import de.lulurobb.keypassmobile.core.sync.RemoteStat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SafRemoteSourceTest {

    @TempDir
    lateinit var dir: File

    private val uri = "content://fake.documents/document/primary%3ADB%2Fa.kdbx"
    private val modified = Instant.parse("2026-10-31T21:38:00Z")
    private val bytes = ByteArray(100_000) { (it % 251).toByte() }

    private val documents = FakeDocuments().apply {
        put(uri, bytes, name = "a.kdbx", modified = modified)
        persisted += uri
    }

    private val source = SafRemoteSource(documents, uri)

    private fun errorOf(block: () -> Unit): RemoteError = assertFailsWith<RemoteException> { block() }.error

    @Test
    fun `stat reports size and modification time without reading the file`() {
        assertEquals(RemoteStat(bytes.size.toLong(), modified), source.stat())
        assertEquals(0, documents.opens[uri] ?: 0)
    }

    @Test
    fun `download copies the whole file`() {
        val target = File(dir, "copy.kdbx")
        source.download(target)
        assertContentEquals(bytes, target.readBytes())
    }

    @Test
    fun `download replaces an existing target`() {
        val target = File(dir, "copy.kdbx").apply { writeBytes(ByteArray(300_000) { 7 }) }
        source.download(target)
        assertContentEquals(bytes, target.readBytes())
    }

    @Test
    fun `a uri without a persisted read permission is access denied before anything is read`() {
        documents.persisted.clear()
        assertEquals(RemoteError.ACCESS_DENIED, errorOf { source.stat() })
        assertEquals(0, documents.queries[uri] ?: 0)
        assertEquals(0, documents.opens[uri] ?: 0)
    }

    @Test
    fun `a permission for another uri does not count`() {
        documents.persisted.clear()
        documents.persisted += "content://fake.documents/document/primary%3ADB%2Fother.kdbx"
        assertEquals(RemoteError.ACCESS_DENIED, errorOf { source.stat() })
    }

    @Test
    fun `a document the provider does not know is not found`() {
        documents.docs.clear()
        assertEquals(RemoteError.NOT_FOUND, errorOf { source.stat() })
    }

    @Test
    fun `a FileNotFoundException from query is not found`() {
        documents.queryErrors[uri] = FileNotFoundException("gone")
        assertEquals(RemoteError.NOT_FOUND, errorOf { source.stat() })
    }

    @Test
    fun `an IllegalArgumentException from query is not found`() {
        documents.queryErrors[uri] = IllegalArgumentException("Unknown URI")
        assertEquals(RemoteError.NOT_FOUND, errorOf { source.stat() })
    }

    @Test
    fun `a SecurityException from query is access denied`() {
        documents.queryErrors[uri] = SecurityException("no")
        assertEquals(RemoteError.ACCESS_DENIED, errorOf { source.stat() })
    }

    @Test
    fun `another IOException from query is other`() {
        documents.queryErrors[uri] = IOException("disk")
        assertEquals(RemoteError.OTHER, errorOf { source.stat() })
    }

    @Test
    fun `a missing file on download is not found`() {
        documents.openErrors[uri] = FileNotFoundException("gone")
        assertEquals(RemoteError.NOT_FOUND, errorOf { source.download(File(dir, "x")) })
    }

    @Test
    fun `an IllegalArgumentException on download is not found`() {
        documents.openErrors[uri] = IllegalArgumentException("Unknown URI")
        assertEquals(RemoteError.NOT_FOUND, errorOf { source.download(File(dir, "x")) })
    }

    @Test
    fun `a SecurityException on download is access denied`() {
        documents.openErrors[uri] = SecurityException("no")
        assertEquals(RemoteError.ACCESS_DENIED, errorOf { source.download(File(dir, "x")) })
    }

    @Test
    fun `another IOException on download is other`() {
        documents.openErrors[uri] = IOException("disk")
        assertEquals(RemoteError.OTHER, errorOf { source.download(File(dir, "x")) })
    }

    @Test
    fun `a read error in the middle of the file is other`() {
        documents.docs.getValue(uri).apply {
            readError = IOException("cut")
            readErrorAfter = 1000
        }
        assertEquals(RemoteError.OTHER, errorOf { source.download(File(dir, "x")) })
    }

    @Test
    fun `a SecurityException while reading is access denied`() {
        documents.docs.getValue(uri).apply {
            readError = SecurityException("revoked")
            readErrorAfter = 10
        }
        assertEquals(RemoteError.ACCESS_DENIED, errorOf { source.download(File(dir, "x")) })
    }

    @Test
    fun `an unknown size is counted from the stream`() {
        documents.docs.getValue(uri).reportSize = false
        assertEquals(RemoteStat(bytes.size.toLong(), modified), source.stat())
        assertEquals(1, documents.opens[uri])
    }

    @Test
    fun `counting an unknown size maps failures too`() {
        documents.docs.getValue(uri).reportSize = false
        documents.openErrors[uri] = FileNotFoundException("gone")
        assertEquals(RemoteError.NOT_FOUND, errorOf { source.stat() })
    }

    @Test
    fun `an unknown modification time is the unknown marker`() {
        documents.docs.getValue(uri).modified = null
        val stat = source.stat()
        assertEquals(RemoteStat.UNKNOWN_MODIFIED, stat.modified)
        assertEquals(bytes.size.toLong(), stat.size)
    }

    @Test
    fun `the error messages carry neither uri nor file name`() {
        documents.queryErrors[uri] = SecurityException("no")
        val message = assertFailsWith<RemoteException> { source.stat() }.message ?: ""
        assertFalse(message.contains("a.kdbx"))
        assertFalse(message.contains("fake.documents"))
    }

    @Test
    fun `close does nothing and may be repeated`() {
        source.close()
        source.close()
        assertEquals(RemoteStat(bytes.size.toLong(), modified), source.stat())
    }
}
