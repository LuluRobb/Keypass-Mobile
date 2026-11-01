package de.lulurobb.keypassmobile.smb

/**
 * Address and account of an SMB server (DS-02). Contains no secret: the password is
 * passed separately to [SmbConnector.connect] as a `CharArray`.
 *
 * @property host IP address or host name. An IP address is recommended because name
 *   resolution is not covered by the connect timeout (DS-05).
 * @property port TCP port, 445 by default.
 * @property domain NTLM domain or workgroup, usually empty.
 * @property username SMB user name.
 */
data class SmbServer(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val domain: String = "",
    val username: String,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port out of range: $port" }
    }

    companion object {
        /** Standard SMB port (direct TCP, no NetBIOS). */
        const val DEFAULT_PORT = 445
    }
}

/**
 * Tunables for [SmbConnector]. The defaults follow DS-05 (short timeouts) and DS-07
 * (signing required, encryption whenever the server offers it).
 *
 * @property connectTimeoutMillis Timeout for establishing the TCP connection.
 * @property responseTimeoutMillis Timeout for each single SMB request (negotiate, login,
 *   directory listing, each read chunk of a download, RPC call). A download of a large
 *   file may take longer in total, but it fails as soon as one chunk stalls.
 * @property requireSigning Reject unsigned sessions, including guest sessions (DS-07).
 * @property encryptIfOffered Use SMB 3 encryption whenever the server supports it (DS-07).
 */
data class SmbOptions(
    val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val responseTimeoutMillis: Long = DEFAULT_RESPONSE_TIMEOUT_MILLIS,
    val requireSigning: Boolean = true,
    val encryptIfOffered: Boolean = true,
) {
    init {
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(responseTimeoutMillis > 0) { "responseTimeoutMillis must be positive" }
    }

    companion object {
        /** Default TCP connect timeout (DS-05). */
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 3_000

        /** Default timeout per SMB request (DS-05). */
        const val DEFAULT_RESPONSE_TIMEOUT_MILLIS = 5_000L
    }
}
