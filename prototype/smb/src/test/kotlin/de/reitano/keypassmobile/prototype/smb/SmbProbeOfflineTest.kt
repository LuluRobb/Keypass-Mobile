package de.reitano.keypassmobile.prototype.smb

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows

/** Timeout and error handling without a real SMB server (DS-05). Runs everywhere. */
class SmbProbeOfflineTest {

    private fun settings(host: String, port: Int, responseTimeoutMillis: Long = SmbSettings.DEFAULT_RESPONSE_TIMEOUT_MILLIS) =
        SmbSettings(
            host = host,
            port = port,
            share = "kpm",
            path = "test.kdbx",
            username = "kpmtest",
            password = "not-a-real-password".toCharArray(),
            responseTimeoutMillis = responseTimeoutMillis,
        )

    @Test
    fun unreachableHostTimesOutAfterConnectTimeout() {
        val start = System.nanoTime()
        val e = assertThrows<SmbProbeException> { SmbProbe(settings("10.255.255.1", 445)).readServerInfo() }
        val millis = (System.nanoTime() - start) / 1_000_000
        println("[offline] unreachable host 10.255.255.1: ${e.kind} after $millis ms: ${e.message}")
        // TIMEOUT on a normal network; UNREACHABLE if the OS rejects the route at once.
        assertTrue(e.kind == SmbProbeException.Kind.TIMEOUT || e.kind == SmbProbeException.Kind.UNREACHABLE)
        assertTrue(millis < 6_000, "took $millis ms")
    }

    @Test
    fun closedPortFailsFast() {
        val port = ServerSocket(0).use { it.localPort }
        val start = System.nanoTime()
        val e = assertThrows<SmbProbeException> { SmbProbe(settings("127.0.0.1", port)).readServerInfo() }
        val millis = (System.nanoTime() - start) / 1_000_000
        println("[offline] closed port: ${e.kind} after $millis ms: ${e.message}")
        assertEquals(SmbProbeException.Kind.UNREACHABLE, e.kind)
    }

    @Test
    fun silentServerTimesOutInsteadOfHanging() {
        // Accepts TCP but never answers the NEGOTIATE request (e.g. a non-SMB device on port 445).
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val accepted = mutableListOf<Socket>()
            val acceptor = thread(isDaemon = true) {
                runCatching { while (true) accepted += server.accept() }
            }
            val start = System.nanoTime()
            val e = assertThrows<SmbProbeException> {
                SmbProbe(settings("127.0.0.1", server.localPort, responseTimeoutMillis = 2_000)).readServerInfo()
            }
            val millis = (System.nanoTime() - start) / 1_000_000
            println("[offline] silent server: ${e.kind} after $millis ms: ${e.message}")
            assertEquals(SmbProbeException.Kind.TIMEOUT, e.kind)
            assertTrue(millis in 1_500..6_000, "took $millis ms")
            accepted.forEach { it.close() }
            acceptor.interrupt()
        }
    }

    @Test
    fun settingsToStringHidesPassword() {
        val s = settings("nas", 445)
        assertFalse(s.toString().contains("not-a-real-password"))
    }
}
