package de.lulurobb.keypassmobile.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import de.lulurobb.keypassmobile.smb.SmbException.Reason
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** NTSTATUS values that smbj 0.15.0 has no enum constant for. */
private const val STATUS_NETWORK_ACCESS_DENIED = 0xC00000CAL
private const val STATUS_OBJECT_PATH_SYNTAX_BAD = 0xC000003BL
private const val STATUS_ACCOUNT_RESTRICTION = 0xC000006EL
private const val STATUS_ACCOUNT_LOCKED_OUT = 0xC0000234L

private val NOT_FOUND_STATUS = setOf(
    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.value,
    NtStatus.STATUS_OBJECT_PATH_NOT_FOUND.value,
    NtStatus.STATUS_NO_SUCH_FILE.value,
    NtStatus.STATUS_NOT_FOUND.value,
    NtStatus.STATUS_OBJECT_NAME_INVALID.value,
    STATUS_OBJECT_PATH_SYNTAX_BAD,
    NtStatus.STATUS_NOT_A_DIRECTORY.value,
    NtStatus.STATUS_FILE_IS_A_DIRECTORY.value,
    NtStatus.STATUS_DELETE_PENDING.value,
)

private val SHARE_NOT_FOUND_STATUS = setOf(
    NtStatus.STATUS_BAD_NETWORK_NAME.value,
    NtStatus.STATUS_BAD_NETWORK_PATH.value,
)

private val ACCESS_DENIED_STATUS = setOf(
    NtStatus.STATUS_ACCESS_DENIED.value,
    STATUS_NETWORK_ACCESS_DENIED,
)

private val CONNECTION_LOST_STATUS = setOf(
    NtStatus.STATUS_NETWORK_NAME_DELETED.value,
    NtStatus.STATUS_USER_SESSION_DELETED.value,
)

/**
 * Maps any exception from smbj, the socket layer or local I/O to [SmbException].
 * Messages only contain the exception class name and NTSTATUS codes, never the text
 * of the underlying exception (SE-07).
 */
internal fun toSmbException(e: Throwable): SmbException {
    if (e is SmbException) return e
    val chain = generateSequence(e) { it.cause }.take(16).toList()

    if (chain.any { it is InterruptedException || (it is InterruptedIOException && it !is SocketTimeoutException) }) {
        // Keep the interrupt visible for the caller (for example a cancelled worker).
        Thread.currentThread().interrupt()
        return SmbException(Reason.OTHER, "Interrupted", cause = e)
    }

    chain.firstNotNullOfOrNull { it as? SMBApiException }?.let { return fromStatus(it, e) }

    return when {
        chain.any { it is SMB2GuestSigningRequiredException } ->
            SmbException(Reason.AUTH_FAILED, "Server only offered an unsigned guest session", cause = e)
        chain.any { it is SocketTimeoutException || it is TimeoutException } ->
            SmbException(Reason.UNREACHABLE, "Timeout: server does not answer", cause = e)
        chain.any { it is UnknownHostException } ->
            SmbException(Reason.UNREACHABLE, "Unknown host", cause = e)
        chain.any { it is SocketException || it is EOFException } ->
            SmbException(Reason.UNREACHABLE, "Connection failed (${chain.last().javaClass.simpleName})", cause = e)
        chain.any { it is TransportException } ->
            SmbException(Reason.PROTOCOL, "Transport error (${chain.last().javaClass.simpleName})", cause = e)
        else ->
            SmbException(Reason.OTHER, "Unexpected error (${chain.last().javaClass.simpleName})", cause = e)
    }
}

private fun fromStatus(api: SMBApiException, e: Throwable): SmbException {
    val code = api.statusCode
    val command = api.failedCommand
    val text = "${command?.name ?: "SMB"} failed with 0x%08X".format(code)
    val reason = when {
        command == SMB2MessageCommandCode.SMB2_SESSION_SETUP -> Reason.AUTH_FAILED
        code == STATUS_ACCOUNT_LOCKED_OUT || code == STATUS_ACCOUNT_RESTRICTION -> Reason.AUTH_FAILED
        code in SHARE_NOT_FOUND_STATUS -> Reason.SHARE_NOT_FOUND
        code in ACCESS_DENIED_STATUS -> Reason.ACCESS_DENIED
        code in NOT_FOUND_STATUS -> Reason.FILE_NOT_FOUND
        code in CONNECTION_LOST_STATUS -> Reason.UNREACHABLE
        code == NtStatus.STATUS_SHARING_VIOLATION.value -> Reason.OTHER
        else -> Reason.PROTOCOL
    }
    return SmbException(reason, text, cause = e)
}

/**
 * Runs [block] and converts every exception to [SmbException]. Errors (for example
 * OutOfMemoryError) are not caught.
 */
internal inline fun <T> smbCall(block: () -> T): T = try {
    block()
} catch (e: Exception) {
    throw toSmbException(e)
}
