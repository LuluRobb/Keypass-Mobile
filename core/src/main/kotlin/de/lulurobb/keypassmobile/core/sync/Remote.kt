package de.lulurobb.keypassmobile.core.sync

import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * The database file of the source: a file on an SMB share (implemented on top
 * of :smb) or a file on the phone picked via the Storage Access Framework.
 * One instance is passed per sync call, so an implementation may open one
 * connection lazily, use it for [stat] and [download], and close it afterwards.
 *
 * Both functions block and throw [RemoteException] on failure. Other
 * exceptions are tolerated and reported as [SyncProblem.REMOTE_ERROR] (or
 * [SyncProblem.UNREACHABLE] for socket timeouts and connection errors).
 */
interface RemoteSource {
    /** Size and last modification time of the remote file. */
    fun stat(): RemoteStat

    /**
     * Downloads the complete remote file into [target], creating or replacing
     * it. A partially written [target] is fine when this throws; the engine
     * deletes it.
     */
    fun download(target: File)
}

/**
 * Size in bytes and last modification time of the remote file (SY-04).
 * [modified] is [UNKNOWN_MODIFIED] when the source cannot tell (a file
 * provider without a modification time).
 */
data class RemoteStat(val size: Long, val modified: Instant) {
    companion object {
        /** Marks an unknown modification time. Compares equal to itself, so change detection falls back to the size. */
        val UNKNOWN_MODIFIED: Instant = Instant.EPOCH
    }
}

/** Why a remote operation failed. */
enum class RemoteError {
    /** Share not reachable or timed out (DS-05), e.g. away from home without VPN. */
    UNREACHABLE,

    /** The server sent a different server GUID than on first setup (DS-04). Nothing was sent to it. */
    SERVER_CHANGED,

    /** Login rejected (wrong SMB user or password). */
    AUTH_FAILED,

    /** Share, folder or file does not exist. */
    NOT_FOUND,

    /** The SMB user may not read the share or file. */
    ACCESS_DENIED,

    /** Anything else. */
    OTHER,
}

/**
 * Failure of a [RemoteSource] call. The message must not contain secrets
 * such as the SMB password (SE-07).
 */
class RemoteException(
    val error: RemoteError,
    message: String = error.name,
    cause: Throwable? = null,
) : IOException(message, cause)
