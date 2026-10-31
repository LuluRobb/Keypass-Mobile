package de.reitano.keypassmobile.prototype.smb

import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * Connection settings for [SmbProbe].
 *
 * [password] is used as given (no copy). The caller owns it and should wipe it
 * with `fill('\u0000')` after the call. [toString] never contains the password.
 */
class SmbSettings(
    val host: String,
    val share: String,
    val path: String,
    val username: String,
    val password: CharArray,
    val domain: String = "",
    val port: Int = DEFAULT_PORT,
    val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val responseTimeoutMillis: Long = DEFAULT_RESPONSE_TIMEOUT_MILLIS,
    /** Reject unsigned responses and guest sessions (DS-07). */
    val requireSigning: Boolean = true,
    /** Offer SMB 3 encryption; used whenever the server supports it (DS-07). */
    val encryptIfOffered: Boolean = true,
) {
    override fun toString(): String =
        "SmbSettings(host=$host, port=$port, share=$share, path=$path, username=$username, domain=$domain)"

    companion object {
        const val DEFAULT_PORT = 445
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 3_000
        const val DEFAULT_RESPONSE_TIMEOUT_MILLIS = 10_000L
    }
}

/** What the server announced in its NEGOTIATE response. Available before authentication. */
data class SmbServerInfo(
    val serverGuid: UUID,
    /** Negotiated dialect, e.g. "SMB 3.1.1". */
    val dialect: String,
    val signingEnabled: Boolean,
    val signingRequired: Boolean,
    /** True if client and server agreed on SMB 3 encryption support. */
    val encryptionOffered: Boolean,
    /** Negotiated cipher, e.g. "AES_128_GCM", or null. */
    val cipher: String?,
    /** TCP connect plus NEGOTIATE. */
    val connectMillis: Long,
)

sealed interface SmbDownloadResult {
    val server: SmbServerInfo

    /** The server GUID did not match. Nothing was authenticated, nothing was written. */
    data class GuidMismatch(
        override val server: SmbServerInfo,
        val expectedGuid: UUID,
        val totalMillis: Long,
    ) : SmbDownloadResult

    data class Downloaded(
        override val server: SmbServerInfo,
        val target: File,
        val size: Long,
        val lastModified: Instant,
        /** Packets of this session are signed (or encrypted, which includes integrity). */
        val sessionSigned: Boolean,
        val sessionEncrypted: Boolean,
        val timings: SmbTimings,
    ) : SmbDownloadResult
}

data class SmbTimings(
    val connectMillis: Long,
    val authMillis: Long,
    /** Tree connect, open and metadata query. */
    val openMillis: Long,
    /** Read, write to the temporary file, fsync and rename. */
    val transferMillis: Long,
    val totalMillis: Long,
) {
    fun throughputMiBPerSecond(bytes: Long): Double =
        if (transferMillis <= 0) Double.NaN else bytes / 1_048_576.0 / (transferMillis / 1000.0)
}

/**
 * Failure of an SMB operation. [message] is meant for display and never contains
 * secrets (SE-07); it does not include the message of the underlying exception.
 */
class SmbProbeException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Kind {
        TIMEOUT,
        UNREACHABLE,
        UNKNOWN_HOST,
        AUTH_FAILED,
        SHARE_NOT_FOUND,
        FILE_NOT_FOUND,
        ACCESS_DENIED,
        INCOMPLETE,
        PROTOCOL,
        LOCAL_IO,
    }
}
