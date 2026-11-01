package de.lulurobb.keypassmobile.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.NamedPipe
import com.hierynomus.smbj.share.PipeShare
import com.hierynomus.smbj.share.Share
import de.lulurobb.keypassmobile.smb.SmbException.Reason
import de.lulurobb.keypassmobile.smb.rpc.RawShare
import de.lulurobb.keypassmobile.smb.rpc.RpcException
import de.lulurobb.keypassmobile.smb.rpc.RpcTransport
import de.lulurobb.keypassmobile.smb.rpc.Srvsvc
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.EnumSet
import java.util.UUID
import com.hierynomus.smbj.share.File as SmbFile

/**
 * An authenticated SMB session, created by [SmbConnector.connect]. Read-only: nothing
 * on the server is ever created, changed or deleted.
 *
 * Paths are relative to the share root and may use `/` or `\` as separator; leading
 * and trailing separators are ignored, so `""` and `"/"` both mean the root.
 *
 * All calls are blocking and bounded by the timeouts of [SmbOptions]. Every failure is
 * reported as [SmbException]. Not thread-safe: use one connection from one thread at a
 * time. Close it after use; [close] never throws.
 */
class SmbConnection internal constructor(
    private val client: SMBClient,
    internal val session: Session,
    /** GUID from the server's NEGOTIATE response (DS-04). Store it at the first sync. */
    val serverGuid: UUID,
) : Closeable {

    private val shares = HashMap<String, DiskShare>()
    private var closed = false

    /** Negotiated dialect, for example "SMB 3.1.1". */
    val dialect: String = session.connection.negotiatedProtocol.dialect.name
        .removePrefix("SMB_").replace('_', '.').let { "SMB $it" }

    /** True if SMB 3 encryption is used for this session (DS-07). */
    val isEncrypted: Boolean = runCatching { session.shouldEncryptData() }.getOrDefault(false)

    /**
     * True if the session's packets are signed. Encrypted sessions are integrity-protected
     * as well, so this is true for them too (DS-07).
     */
    val isSigned: Boolean = isEncrypted || run {
        val context = session.sessionContext
        if (session.connection.negotiatedProtocol.dialect.isSmb3x) {
            context.signingKey != null
        } else {
            context.sessionKey != null
        }
    }

    /**
     * Lists the disk shares of the server via SRVSVC NetShareEnumAll (level 1) over the
     * IPC$ pipe `srvsvc`. IPC$, ADMIN$, C$, print$, printer queues and all other shares
     * ending in `$` are left out. Sorted by name (German collation).
     *
     * @throws SmbException with [Reason.ACCESS_DENIED] if the server denies the listing,
     *   [Reason.SHARE_LISTING_UNSUPPORTED] for every other failure. In both cases the
     *   UI lets the user type the share name (DS-02).
     */
    fun listShares(): List<ShareInfo> {
        checkOpen()
        try {
            return toDiskShares(enumerateShares(session))
        } catch (e: Exception) {
            val denied = (e is RpcException && e.accessDenied) ||
                (e !is RpcException && toSmbException(e).reason == Reason.ACCESS_DENIED)
            throw if (denied) {
                SmbException(Reason.ACCESS_DENIED, "Share listing denied", cause = e)
            } else {
                SmbException(
                    Reason.SHARE_LISTING_UNSUPPORTED,
                    "Share listing failed (${e.javaClass.simpleName})",
                    cause = e,
                )
            }
        }
    }

    /**
     * Lists a folder of [share]: only folders and `.kdbx` files (case-insensitive),
     * without "." and "..". Folders come first, then files, each group sorted by name
     * with German collation.
     *
     * @param path Folder path relative to the share root, `""` for the root.
     * @throws SmbException [Reason.SHARE_NOT_FOUND], [Reason.FILE_NOT_FOUND] (also when
     *   [path] is a file), [Reason.ACCESS_DENIED], [Reason.UNREACHABLE] and others.
     */
    fun listFolder(share: String, path: String): List<FolderItem> = smbCall {
        checkOpen()
        val entries = diskShare(share).list(normalizePath(path)).map {
            RawEntry(it.fileName, it.fileAttributes, it.endOfFile, it.lastWriteTime.toInstant())
        }
        toFolderItems(entries)
    }

    /**
     * Reads size and last write time of a file (SY-04).
     *
     * @throws SmbException [Reason.FILE_NOT_FOUND] if the file does not exist or [path] is
     *   a folder, [Reason.SHARE_NOT_FOUND], [Reason.ACCESS_DENIED] and others.
     */
    fun stat(share: String, path: String): FileStat = smbCall {
        checkOpen()
        openForRead(diskShare(share), path, EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES)).use { file ->
            fileStat(file)
        }
    }

    /**
     * Downloads a file to [target]: streams it, then flushes and fsyncs [target] and
     * checks that the number of bytes matches the size reported when the file was opened.
     *
     * [target] is created or overwritten only after the remote file was opened
     * successfully. If the transfer fails afterwards, [target] is deleted, so it never
     * holds a partial file. Callers that must keep an older file pass a temporary path
     * and rename it themselves (SY-05). The parent folder of [target] must exist.
     *
     * The file is opened with full share access, so KeePassXC on the PC is not blocked.
     *
     * @return Size and last write time of the downloaded file.
     * @throws SmbException [Reason.FILE_NOT_FOUND], [Reason.SHARE_NOT_FOUND],
     *   [Reason.ACCESS_DENIED], [Reason.UNREACHABLE] (connection lost or stalled),
     *   [Reason.PROTOCOL] (size mismatch), [Reason.OTHER] (local file error) and others.
     */
    fun download(share: String, path: String, target: File): FileStat = smbCall {
        checkOpen()
        val disk = diskShare(share)
        openForRead(disk, path, EnumSet.of(AccessMask.GENERIC_READ)).use { file ->
            val stat = fileStat(file)
            var complete = false
            try {
                val written = copyTo(file, target, readBufferSize())
                if (written != stat.size) {
                    throw SmbException(Reason.PROTOCOL, "Download incomplete: $written of ${stat.size} bytes")
                }
                complete = true
            } finally {
                if (!complete) target.delete()
            }
            stat
        }
    }

    /** Disconnects all shares, logs off and closes the TCP connection. Never throws. */
    override fun close() {
        if (closed) return
        closed = true
        shares.values.forEach { quietly { it.close() } }
        shares.clear()
        quietly { session.close() }
        quietly { client.close() }
    }

    private fun checkOpen() {
        if (closed) throw SmbException(Reason.OTHER, "Connection already closed")
    }

    /** Tree connect, cached per share name (case-insensitive like SMB itself). */
    private fun diskShare(name: String): DiskShare {
        val key = name.trim().lowercase()
        if (key.isEmpty() || key.any { it == '/' || it == '\\' }) {
            throw SmbException(Reason.SHARE_NOT_FOUND, "Invalid share name")
        }
        shares[key]?.takeIf { it.isConnected }?.let { return it }
        val share: Share = session.connectShare(name.trim())
        if (share !is DiskShare) {
            quietly { share.close() }
            throw SmbException(Reason.SHARE_NOT_FOUND, "Not a disk share")
        }
        shares[key] = share
        return share
    }

    private fun openForRead(share: DiskShare, path: String, access: Set<AccessMask>): SmbFile {
        val normalized = normalizePath(path)
        if (normalized.isEmpty()) throw SmbException(Reason.FILE_NOT_FOUND, "No file name given")
        return share.openFile(
            normalized,
            access,
            null,
            // Do not block KeePassXC on the PC while we read.
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            // Opening a folder fails with STATUS_FILE_IS_A_DIRECTORY (FILE_NOT_FOUND).
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
    }

    private fun fileStat(file: SmbFile): FileStat {
        val info = file.fileInformation
        return FileStat(info.standardInformation.endOfFile, info.basicInformation.lastWriteTime.toInstant())
    }

    private fun readBufferSize(): Int =
        session.connection.negotiatedProtocol.maxReadSize.coerceIn(64 * 1024, 1024 * 1024)

    /** Copies the remote file into [target]; returns the number of bytes written. */
    private fun copyTo(file: SmbFile, target: File, bufferSize: Int): Long {
        var total = 0L
        val out = local { FileOutputStream(target) }
        out.use {
            file.inputStream.use { input ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    local { out.write(buffer, 0, n) }
                    total += n
                }
            }
            local {
                out.flush()
                out.fd.sync()
            }
        }
        return total
    }

    private inline fun <T> local(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw SmbException(Reason.OTHER, "Local file error (${e.javaClass.simpleName})", cause = e)
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Best effort while closing.
        }
    }

}

/**
 * Runs SRVSVC NetShareEnumAll over the pipe IPC$\srvsvc of [session] and returns the
 * unfiltered result. [wrap] lets tests observe the pipe traffic.
 */
internal fun enumerateShares(
    session: Session,
    wrap: (RpcTransport) -> RpcTransport = { it },
): List<RawShare> = session.connectShare("IPC$").use { ipc ->
    val pipes = ipc as? PipeShare ?: throw RpcException("IPC$ is not a pipe share")
    pipes.open(
        Srvsvc.PIPE_NAME,
        SMB2ImpersonationLevel.Impersonation,
        EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_WRITE_DATA, AccessMask.FILE_READ_ATTRIBUTES),
        null,
        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
        SMB2CreateDisposition.FILE_OPEN,
        null,
    ).use { pipe ->
        Srvsvc.netShareEnumAll(wrap(NamedPipeTransport(pipe)))
    }
}

/** DCE/RPC messages over an smbj named pipe; one message per PDU fragment. */
private class NamedPipeTransport(private val pipe: NamedPipe) : RpcTransport {
    override fun transact(pdu: ByteArray): ByteArray = pipe.transact(pdu)

    override fun read(maxLength: Int): ByteArray {
        val buffer = ByteArray(maxLength)
        val n = pipe.read(buffer)
        return if (n <= 0) ByteArray(0) else buffer.copyOf(n)
    }
}
