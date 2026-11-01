package de.lulurobb.keypassmobile.platform.smb

import de.lulurobb.keypassmobile.core.sync.RemoteError
import de.lulurobb.keypassmobile.core.sync.RemoteException
import de.lulurobb.keypassmobile.core.sync.RemoteStat
import de.lulurobb.keypassmobile.platform.session.RemoteHandle
import de.lulurobb.keypassmobile.smb.SmbConnection
import de.lulurobb.keypassmobile.smb.SmbConnector
import de.lulurobb.keypassmobile.smb.SmbException
import de.lulurobb.keypassmobile.smb.SmbServer
import java.io.File
import java.util.UUID

/** Learns server GUIDs from sync connections (DS-04). */
internal interface ServerGuidListener {
    /** Connected and logged in; [guid] is the server's GUID. */
    fun onConnected(guid: UUID)

    /** The server sent [guid] instead of the expected one; nothing was sent to it. */
    fun onServerChanged(guid: UUID)
}

/**
 * The database file on the SMB share as [RemoteHandle] for one sync call.
 * Connects lazily at the first call, uses the same connection for stat and
 * download and closes it in [close]. The SMB password is decrypted from the
 * keystore just for the login and wiped right after (DS-03, SE-04).
 *
 * @param expectedGuid remembered server GUID; a different one aborts before
 *   the login (DS-04). Null accepts any server (first sync).
 */
internal class SmbRemoteSource(
    private val connector: SmbConnector,
    private val server: SmbServer,
    private val share: String,
    private val path: String,
    private val expectedGuid: UUID?,
    private val password: () -> CharArray?,
    private val listener: ServerGuidListener,
) : RemoteHandle {

    private var connection: SmbConnection? = null

    override fun stat(): RemoteStat = smbCall {
        val stat = connection().stat(share, path)
        RemoteStat(stat.size, stat.modified)
    }

    override fun download(target: File) {
        smbCall { connection().download(share, path, target) }
    }

    override fun close() {
        connection?.close()
        connection = null
    }

    private fun connection(): SmbConnection = connection ?: connect().also { connection = it }

    private fun connect(): SmbConnection {
        val chars = password() ?: throw RemoteException(RemoteError.AUTH_FAILED, "No stored SMB password")
        try {
            val connected = connector.connect(server, chars, expectedGuid)
            listener.onConnected(connected.serverGuid)
            return connected
        } catch (e: SmbException) {
            val changed = e.serverGuid
            if (e.reason == SmbException.Reason.SERVER_CHANGED && changed != null) listener.onServerChanged(changed)
            throw e
        } finally {
            chars.fill('\u0000')
        }
    }

    private inline fun <T> smbCall(block: () -> T): T = try {
        block()
    } catch (e: SmbException) {
        throw RemoteException(remoteErrorOf(e.reason), "SMB ${e.reason}", e)
    }
}

/** Maps :smb failures to the categories of the sync engine. */
internal fun remoteErrorOf(reason: SmbException.Reason): RemoteError = when (reason) {
    SmbException.Reason.UNREACHABLE -> RemoteError.UNREACHABLE
    SmbException.Reason.AUTH_FAILED -> RemoteError.AUTH_FAILED
    SmbException.Reason.SERVER_CHANGED -> RemoteError.SERVER_CHANGED
    SmbException.Reason.SHARE_NOT_FOUND, SmbException.Reason.FILE_NOT_FOUND -> RemoteError.NOT_FOUND
    SmbException.Reason.ACCESS_DENIED -> RemoteError.ACCESS_DENIED
    SmbException.Reason.SHARE_LISTING_UNSUPPORTED,
    SmbException.Reason.PROTOCOL,
    SmbException.Reason.OTHER,
    -> RemoteError.OTHER
}

/** SMB path of a file below the share from its segments. */
internal fun smbPath(segments: List<String>): String = segments.joinToString("/")
