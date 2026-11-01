package de.lulurobb.keypassmobile.smb

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.session.Session
import de.lulurobb.keypassmobile.smb.SmbException.Reason
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Entry point of the module: opens authenticated SMB 2/3 connections with smbj.
 *
 * All calls are blocking and must not run on the Android main thread. Each step is
 * bounded by [SmbOptions] timeouts (DS-05). The connector itself holds no state and
 * may be shared between threads.
 *
 * Cryptography uses smbj's [BCSecurityProvider], i.e. the BouncyCastle lightweight API
 * directly, without registering a JCA provider. This works the same on the JVM and on
 * Android, whose built-in providers lack MD4 (needed for NTLM).
 */
class SmbConnector(val options: SmbOptions = SmbOptions()) {

    /**
     * Connects to [server], reads the server GUID from the NEGOTIATE response and, only
     * if it matches [expectedGuid], logs in with NTLM.
     *
     * Order of events (DS-04): TCP connect, NEGOTIATE (SMB 2.0.2 to 3.1.1, no SMB 1),
     * GUID check, SESSION_SETUP. On a GUID mismatch the connection is closed before
     * SESSION_SETUP, so neither user name nor password hash reach the server.
     *
     * @param server Address and user.
     * @param password Passed through to smbj as is. smbj copies it; the copy is wiped
     *   after the login. The caller owns [password] and should wipe it after this call.
     *   smbj turns the password into a String internally for NTLM (known limitation,
     *   SE-04).
     * @param expectedGuid GUID stored at the first successful sync, or null to accept any
     *   server (first connection; read the result from [SmbConnection.serverGuid]).
     * @return An authenticated connection; close it after use.
     * @throws SmbException with [Reason.SERVER_CHANGED] and [SmbException.serverGuid] set
     *   when the GUID differs, [Reason.UNREACHABLE] on network errors and timeouts,
     *   [Reason.AUTH_FAILED] when the login is rejected (also for guest-only servers when
     *   signing is required), [Reason.PROTOCOL] or [Reason.OTHER] otherwise.
     */
    fun connect(server: SmbServer, password: CharArray, expectedGuid: UUID?): SmbConnection {
        val client = SMBClient(smbConfig())
        try {
            val connection = client.connect(server.host, server.port)
            val context = connection.connectionContext
            val serverGuid: UUID = context.serverGuid
            if (expectedGuid != null && expectedGuid != serverGuid) {
                throw SmbException(
                    Reason.SERVER_CHANGED,
                    "Server GUID differs from the expected one; not authenticated",
                    serverGuid = serverGuid,
                )
            }
            val auth = AuthenticationContext(server.username, password, server.domain)
            val session: Session = try {
                connection.authenticate(auth)
            } finally {
                // AuthenticationContext holds its own copy of the password.
                auth.password.fill('\u0000')
            }
            return SmbConnection(client, session, serverGuid)
        } catch (e: Exception) {
            closeQuietly(client)
            throw toSmbException(e)
        }
    }

    private fun smbConfig(): SmbConfig = SmbConfig.builder()
        // SMB 2/3 only. The negotiate request is a pure SMB2 packet, no SMB1 fallback.
        .withDialects(
            SMB2Dialect.SMB_3_1_1,
            SMB2Dialect.SMB_3_0_2,
            SMB2Dialect.SMB_3_0,
            SMB2Dialect.SMB_2_1,
            SMB2Dialect.SMB_2_0_2,
        )
        .withMultiProtocolNegotiate(false)
        // BouncyCastle lightweight API, independent of the JCA providers. Needed on
        // Android, whose built-in providers have no MD4 (NTLM).
        .withSecurityProvider(BCSecurityProvider())
        // NTLM only, same on the JVM and on Android (no Kerberos/SPNEGO via JGSS).
        .withAuthenticators(NtlmAuthenticator.Factory())
        .withSocketFactory(ProxySocketFactory(options.connectTimeoutMillis))
        .withTimeout(options.responseTimeoutMillis, TimeUnit.MILLISECONDS)
        .withSigningRequired(options.requireSigning)
        .withEncryptData(options.encryptIfOffered)
        .withDfsEnabled(false)
        .build()

    private fun closeQuietly(client: SMBClient) {
        try {
            client.close()
        } catch (_: Exception) {
            // Nothing useful to do; the original failure is reported.
        }
    }
}
