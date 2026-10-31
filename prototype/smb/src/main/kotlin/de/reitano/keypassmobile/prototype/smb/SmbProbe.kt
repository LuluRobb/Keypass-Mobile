package de.reitano.keypassmobile.prototype.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import de.reitano.keypassmobile.prototype.smb.SmbProbeException.Kind
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.hierynomus.smbj.share.File as SmbFile

/**
 * Prototype 3: SMB 2/3 download with smbj. The server GUID is read from the
 * NEGOTIATE response, i.e. after connecting but before any credentials are sent.
 *
 * Every call uses its own [SMBClient], so nothing is cached between calls.
 * All failures are reported as [SmbProbeException].
 */
class SmbProbe(private val settings: SmbSettings) {

    /** Connect and negotiate only. No authentication. */
    fun readServerInfo(): SmbServerInfo = guarded {
        val start = System.nanoTime()
        SMBClient(config()).use { client ->
            client.connect(settings.host, settings.port).use { connection ->
                serverInfo(connection, millisSince(start))
            }
        }
    }

    /**
     * Connect, compare the server GUID with [expectedGuid] (skipped when null) and
     * only then authenticate. Downloads [SmbSettings.path] into a temporary file next
     * to [target] and renames it atomically, so [target] is never a partial file.
     */
    fun download(expectedGuid: UUID?, target: File): SmbDownloadResult = guarded {
        val start = System.nanoTime()
        SMBClient(config()).use { client ->
            client.connect(settings.host, settings.port).use { connection ->
                val connectMillis = millisSince(start)
                val server = serverInfo(connection, connectMillis)
                if (expectedGuid != null && expectedGuid != server.serverGuid) {
                    return@guarded SmbDownloadResult.GuidMismatch(server, expectedGuid, millisSince(start))
                }

                val authStart = System.nanoTime()
                val auth = AuthenticationContext(settings.username, settings.password, settings.domain)
                val session = try {
                    connection.authenticate(auth)
                } finally {
                    // AuthenticationContext keeps its own copy of the password.
                    auth.password.fill('\u0000')
                }
                val authMillis = millisSince(authStart)

                session.use {
                    val openStart = System.nanoTime()
                    val share = session.connectShare(settings.share) as? DiskShare
                        ?: throw SmbProbeException(Kind.SHARE_NOT_FOUND, "Freigabe ist keine Datei-Freigabe")
                    share.use {
                        openForRead(share).use { file ->
                            val info = file.fileInformation
                            if (info.standardInformation.isDirectory) {
                                throw SmbProbeException(Kind.FILE_NOT_FOUND, "Pfad ist ein Ordner, keine Datei")
                            }
                            val size = info.standardInformation.endOfFile
                            val lastModified = info.basicInformation.lastWriteTime.toInstant()
                            val openMillis = millisSince(openStart)

                            val transferStart = System.nanoTime()
                            val bufferSize = connection.negotiatedProtocol.maxReadSize.coerceIn(64 * 1024, 1024 * 1024)
                            copyAtomically(file, size, target, bufferSize)
                            val transferMillis = millisSince(transferStart)

                            SmbDownloadResult.Downloaded(
                                server = server,
                                target = target,
                                size = size,
                                lastModified = lastModified,
                                sessionSigned = isSigned(session),
                                sessionEncrypted = session.shouldEncryptData(),
                                timings = SmbTimings(connectMillis, authMillis, openMillis, transferMillis, millisSince(start)),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun config(): SmbConfig = SmbConfig.builder()
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
        .withSocketFactory(ProxySocketFactory(settings.connectTimeoutMillis))
        .withTimeout(settings.responseTimeoutMillis, TimeUnit.MILLISECONDS)
        .withSigningRequired(settings.requireSigning)
        .withEncryptData(settings.encryptIfOffered)
        .withDfsEnabled(false)
        .build()

    private fun serverInfo(connection: Connection, connectMillis: Long): SmbServerInfo {
        val context = connection.connectionContext
        val dialect = context.negotiatedProtocol.dialect
        return SmbServerInfo(
            serverGuid = context.serverGuid,
            dialect = "SMB " + dialect.name.removePrefix("SMB_").replace('_', '.'),
            signingEnabled = context.isServerSigningEnabled,
            signingRequired = context.isServerRequiresSigning,
            encryptionOffered = dialect.isSmb3x && context.supportsEncryption(),
            cipher = context.cipherId?.name,
            connectMillis = connectMillis,
        )
    }

    private fun openForRead(share: DiskShare): SmbFile = share.openFile(
        settings.path.trim().trimStart('/', '\\'),
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        // Do not block KeePassXC on the PC while we read.
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
    )

    private fun isSigned(session: Session): Boolean {
        val context = session.sessionContext
        val dialect = session.connection.negotiatedProtocol.dialect
        return if (dialect.isSmb3x) context.signingKey != null else context.sessionKey != null
    }

    private fun copyAtomically(file: SmbFile, expectedSize: Long, target: File, bufferSize: Int) {
        val dir = target.absoluteFile.parentFile
        val temp = local { dir.mkdirs(); File.createTempFile(target.name + ".", ".part", dir) }
        var moved = false
        try {
            var total = 0L
            local { FileOutputStream(temp) }.use { out ->
                file.inputStream.use { input ->
                    val buffer = ByteArray(bufferSize)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        local { out.write(buffer, 0, n) }
                        total += n
                    }
                }
                local { out.fd.sync() }
            }
            if (total != expectedSize) {
                throw SmbProbeException(
                    Kind.INCOMPLETE,
                    "Unvollständig: $total von $expectedSize Bytes gelesen",
                )
            }
            local {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            moved = true
        } finally {
            if (!moved) temp.delete()
        }
    }

    private inline fun <T> local(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw SmbProbeException(Kind.LOCAL_IO, "Lokaler Dateifehler (${e.javaClass.simpleName})", e)
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw toProbeException(e)
    }

    private fun millisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
}

/** Maps smbj and socket exceptions to [SmbProbeException] with a display message without secrets. */
internal fun toProbeException(e: Throwable): SmbProbeException {
    if (e is SmbProbeException) return e
    val chain = generateSequence(e) { it.cause }.take(16).toList()

    chain.firstNotNullOfOrNull { it as? SMBApiException }?.let { api ->
        val status = api.status
        val code = "${status.name}, 0x%08X".format(api.statusCode)
        return when {
            api.failedCommand == SMB2MessageCommandCode.SMB2_SESSION_SETUP ->
                SmbProbeException(Kind.AUTH_FAILED, "Anmeldung fehlgeschlagen ($code)", e)
            status == NtStatus.STATUS_BAD_NETWORK_NAME ->
                SmbProbeException(Kind.SHARE_NOT_FOUND, "Freigabe nicht gefunden ($code)", e)
            status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ->
                SmbProbeException(Kind.FILE_NOT_FOUND, "Datei nicht gefunden ($code)", e)
            status == NtStatus.STATUS_ACCESS_DENIED ->
                SmbProbeException(Kind.ACCESS_DENIED, "Zugriff verweigert ($code)", e)
            else ->
                SmbProbeException(Kind.PROTOCOL, "SMB-Fehler bei ${api.failedCommand} ($code)", e)
        }
    }
    return when {
        chain.any { it is SMB2GuestSigningRequiredException } ->
            SmbProbeException(Kind.AUTH_FAILED, "Server hat nur eine Gast-Sitzung angeboten (ohne Signierung, abgelehnt)", e)
        chain.any { it is SocketTimeoutException || it is TimeoutException } ->
            SmbProbeException(Kind.TIMEOUT, "Zeitüberschreitung: Server antwortet nicht", e)
        chain.any { it is UnknownHostException } ->
            SmbProbeException(Kind.UNKNOWN_HOST, "Hostname unbekannt", e)
        chain.any { it is ConnectException } ->
            SmbProbeException(Kind.UNREACHABLE, "Verbindung abgelehnt (Port geschlossen?)", e)
        chain.any { it is NoRouteToHostException } ->
            SmbProbeException(Kind.UNREACHABLE, "Keine Route zum Host", e)
        chain.any { it is TransportException } ->
            SmbProbeException(Kind.PROTOCOL, "Verbindungsfehler (${chain.last().javaClass.simpleName})", e)
        else ->
            SmbProbeException(Kind.PROTOCOL, "Unerwarteter Fehler (${e.javaClass.simpleName})", e)
    }
}
