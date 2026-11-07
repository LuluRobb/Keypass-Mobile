package de.lulurobb.keypassmobile.platform.saf

import de.lulurobb.keypassmobile.core.sync.RemoteError
import de.lulurobb.keypassmobile.core.sync.RemoteException
import de.lulurobb.keypassmobile.core.sync.RemoteStat
import de.lulurobb.keypassmobile.platform.session.RemoteHandle
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The database file on the phone as [RemoteHandle] for one sync call (DS-08,
 * SY-09). Reads through [documents]; holds nothing open between the calls, so
 * [close] has nothing to do.
 *
 * Mapping of failures: a uri without a persisted read permission, or a
 * SecurityException, is ACCESS_DENIED; a missing document (null from query,
 * FileNotFoundException, IllegalArgumentException) is NOT_FOUND; any other
 * IOException is OTHER. A missing size is counted from the stream, a missing
 * modification time is RemoteStat.UNKNOWN_MODIFIED. The messages carry no uri
 * and no file name.
 */
internal class SafRemoteSource(
    private val documents: DocumentAccess,
    private val uri: String,
) : RemoteHandle {

    override fun stat(): RemoteStat {
        // A lost grant is reported even when the provider still answers for another reason.
        if (uri !in documents.persistedReadUris()) {
            throw RemoteException(RemoteError.ACCESS_DENIED, "No persisted read permission")
        }
        val info = mapped { documents.query(uri) } ?: throw RemoteException(RemoteError.NOT_FOUND, "Document not found")
        val size = info.sizeBytes ?: countBytes()
        return RemoteStat(size, info.lastModified ?: RemoteStat.UNKNOWN_MODIFIED)
    }

    override fun download(target: File) {
        val buffer = ByteArray(BUFFER_SIZE)
        mapped { documents.open(uri) }.use { input ->
            target.outputStream().use { output ->
                while (true) {
                    val read = mapped { input.read(buffer) }
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    override fun close() = Unit

    private fun countBytes(): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        mapped { documents.open(uri) }.use { input ->
            while (true) {
                val read = mapped { input.read(buffer) }
                if (read < 0) break
                total += read
            }
        }
        return total
    }

    /** Runs a document call and turns its failures into [RemoteException]. */
    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (e: RemoteException) {
        throw e
    } catch (e: FileNotFoundException) {
        throw RemoteException(RemoteError.NOT_FOUND, "Document not found", e)
    } catch (e: IllegalArgumentException) {
        throw RemoteException(RemoteError.NOT_FOUND, "Document not found", e)
    } catch (e: SecurityException) {
        throw RemoteException(RemoteError.ACCESS_DENIED, "No access to the document", e)
    } catch (e: IOException) {
        throw RemoteException(RemoteError.OTHER, "Document could not be read", e)
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
