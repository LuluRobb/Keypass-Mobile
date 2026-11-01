package de.lulurobb.keypassmobile.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import de.lulurobb.keypassmobile.smb.SmbException.Reason
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows

/** Timeouts (DS-05) and error mapping without a real SMB server. Runs everywhere. */
class SmbOfflineTest {

    private val password = "not-a-real-password"

    private fun server(host: String, port: Int) = SmbServer(host = host, port = port, username = "kpmtest")

    private fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun unreachableHostFailsWithinConnectTimeout() {
        val connector = SmbConnector(SmbOptions(connectTimeoutMillis = 1_500))
        lateinit var e: SmbException
        val millis = timed {
            e = assertThrows { connector.connect(server("10.255.255.1", 445), password.toCharArray(), null) }
        }
        println("[offline] unreachable host: $e after $millis ms")
        assertEquals(Reason.UNREACHABLE, e.reason)
        assertTrue(millis < 4_000, "took $millis ms")
        assertFalse(e.toString().contains(password))
    }

    @Test
    fun closedPortFailsFast() {
        val port = ServerSocket(0).use { it.localPort }
        lateinit var e: SmbException
        val millis = timed {
            e = assertThrows { SmbConnector().connect(server("127.0.0.1", port), password.toCharArray(), UUID.randomUUID()) }
        }
        println("[offline] closed port: $e after $millis ms")
        assertEquals(Reason.UNREACHABLE, e.reason)
        assertTrue(millis < 4_000, "took $millis ms")
    }

    @Test
    fun silentServerTimesOutInsteadOfHanging() {
        // Accepts TCP but never answers the NEGOTIATE request (e.g. a non-SMB device on port 445).
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val accepted = mutableListOf<Socket>()
            val acceptor = thread(isDaemon = true) {
                runCatching { while (true) accepted += listener.accept() }
            }
            val connector = SmbConnector(SmbOptions(responseTimeoutMillis = 1_500))
            lateinit var e: SmbException
            val millis = timed {
                e = assertThrows { connector.connect(server("127.0.0.1", listener.localPort), password.toCharArray(), null) }
            }
            println("[offline] silent server: $e after $millis ms")
            assertEquals(Reason.UNREACHABLE, e.reason)
            assertTrue(millis in 1_000..5_000, "took $millis ms")
            accepted.forEach { it.close() }
            acceptor.interrupt()
        }
    }

    @Test
    fun passwordIsNotModifiedByConnect() {
        val pw = password.toCharArray()
        val port = ServerSocket(0).use { it.localPort }
        assertThrows<SmbException> { SmbConnector().connect(server("127.0.0.1", port), pw, null) }
        assertEquals(password, String(pw))
    }

    @Test
    fun serverAndOptionsAreValidated() {
        assertThrows<IllegalArgumentException> { SmbServer(host = " ", username = "u") }
        assertThrows<IllegalArgumentException> { SmbServer(host = "nas", port = 0, username = "u") }
        assertThrows<IllegalArgumentException> { SmbServer(host = "nas", port = 65536, username = "u") }
        assertThrows<IllegalArgumentException> { SmbOptions(connectTimeoutMillis = 0) }
        assertThrows<IllegalArgumentException> { SmbOptions(responseTimeoutMillis = -1) }
        assertEquals(445, SmbServer(host = "nas", username = "u").port)
    }

    // --- error mapping ---

    private fun api(status: NtStatus, command: SMB2MessageCommandCode) =
        SMBApiException(status.value, command, "detail with secret-text", null)

    @Test
    fun smbStatusCodesMapToReasons() {
        assertEquals(Reason.AUTH_FAILED, toSmbException(api(NtStatus.STATUS_LOGON_FAILURE, SMB2MessageCommandCode.SMB2_SESSION_SETUP)).reason)
        assertEquals(Reason.AUTH_FAILED, toSmbException(api(NtStatus.STATUS_ACCOUNT_DISABLED, SMB2MessageCommandCode.SMB2_SESSION_SETUP)).reason)
        assertEquals(Reason.SHARE_NOT_FOUND, toSmbException(api(NtStatus.STATUS_BAD_NETWORK_NAME, SMB2MessageCommandCode.SMB2_TREE_CONNECT)).reason)
        assertEquals(Reason.ACCESS_DENIED, toSmbException(api(NtStatus.STATUS_ACCESS_DENIED, SMB2MessageCommandCode.SMB2_TREE_CONNECT)).reason)
        assertEquals(Reason.ACCESS_DENIED, toSmbException(api(NtStatus.STATUS_ACCESS_DENIED, SMB2MessageCommandCode.SMB2_CREATE)).reason)
        assertEquals(Reason.FILE_NOT_FOUND, toSmbException(api(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, SMB2MessageCommandCode.SMB2_CREATE)).reason)
        assertEquals(Reason.FILE_NOT_FOUND, toSmbException(api(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, SMB2MessageCommandCode.SMB2_CREATE)).reason)
        assertEquals(Reason.FILE_NOT_FOUND, toSmbException(api(NtStatus.STATUS_FILE_IS_A_DIRECTORY, SMB2MessageCommandCode.SMB2_CREATE)).reason)
        assertEquals(Reason.FILE_NOT_FOUND, toSmbException(api(NtStatus.STATUS_NOT_A_DIRECTORY, SMB2MessageCommandCode.SMB2_CREATE)).reason)
        assertEquals(Reason.UNREACHABLE, toSmbException(api(NtStatus.STATUS_NETWORK_NAME_DELETED, SMB2MessageCommandCode.SMB2_READ)).reason)
        assertEquals(Reason.PROTOCOL, toSmbException(api(NtStatus.STATUS_INVALID_PARAMETER, SMB2MessageCommandCode.SMB2_READ)).reason)
    }

    @Test
    fun transportErrorsMapToUnreachable() {
        assertEquals(Reason.UNREACHABLE, toSmbException(TransportException(ExecutionException(TimeoutException()))).reason)
        assertEquals(Reason.UNREACHABLE, toSmbException(SocketTimeoutException("x")).reason)
        assertEquals(Reason.UNREACHABLE, toSmbException(ConnectException("refused")).reason)
        assertEquals(Reason.UNREACHABLE, toSmbException(UnknownHostException("nas")).reason)
        assertEquals(Reason.PROTOCOL, toSmbException(TransportException("garbage")).reason)
        assertEquals(Reason.OTHER, toSmbException(IllegalStateException("x")).reason)
    }

    @Test
    fun interruptIsPreserved() {
        val e = toSmbException(IOException(InterruptedException()))
        assertEquals(Reason.OTHER, e.reason)
        assertTrue(Thread.interrupted(), "interrupt flag must be set again (and is cleared here)")
    }

    @Test
    fun messagesNeverContainUnderlyingText() {
        val secret = "secret-text"
        val mapped = listOf(
            toSmbException(api(NtStatus.STATUS_LOGON_FAILURE, SMB2MessageCommandCode.SMB2_SESSION_SETUP)),
            toSmbException(ConnectException(secret)),
            toSmbException(IllegalStateException(secret)),
            toSmbException(TransportException(secret)),
        )
        for (e in mapped) {
            assertFalse(e.message.orEmpty().contains(secret), e.message)
            assertFalse(e.toString().contains(secret), e.toString())
        }
    }

    @Test
    fun smbExceptionPassesThroughAndCarriesGuid() {
        val guid = UUID.randomUUID()
        val original = SmbException(Reason.SERVER_CHANGED, "changed", serverGuid = guid)
        assertSame(original, toSmbException(original))
        assertTrue(original.toString().contains(guid.toString()))
        assertNull(SmbException(Reason.OTHER, "x").serverGuid)
    }
}
