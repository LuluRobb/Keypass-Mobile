package de.reitano.keypassmobile.prototype.smb

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

/** SmbProbe against Samba 4.23 in Docker on host port 1445. Skipped without Docker. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmbProbeSambaTest {

    private lateinit var container: SambaContainer
    private lateinit var shareDir: File
    private lateinit var workDir: File
    private lateinit var testFile: File
    private lateinit var bigFile: File

    @BeforeAll
    fun startSamba() {
        SambaContainer.assumeDocker()
        shareDir = Files.createTempDirectory("kpm-smb-share").toFile()
        workDir = Files.createTempDirectory("kpm-smb-target").toFile()
        testFile = File(shareDir, "test.kdbx").apply { writeBytes(Random(42).nextBytes(4 * 1024 * 1024)) }
        bigFile = File(shareDir, "big.bin").apply { writeBytes(Random(7).nextBytes(32 * 1024 * 1024)) }
        container = SambaContainer("kpm-smb-test", 1445, hostname = "kpm-nas", shareDir = shareDir)
        container.start()
        container.awaitReady()
    }

    @AfterAll
    fun stopSamba() {
        if (::container.isInitialized) container.remove()
        if (::shareDir.isInitialized) shareDir.deleteRecursively()
        if (::workDir.isInitialized) workDir.deleteRecursively()
    }

    private fun probe(settings: SmbSettings = container.settings()) = SmbProbe(settings)

    private fun authLogLines(): List<String> = container.logs().lines().filter { it.contains("Auth: [") }

    /** Waits until docker logs has caught up with smbd's stdout. */
    private fun authLogLinesSettled(): List<String> {
        var last = authLogLines()
        repeat(10) {
            Thread.sleep(300)
            val now = authLogLines()
            if (now == last) return now
            last = now
        }
        return last
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun leftoverParts() = workDir.listFiles().orEmpty().filter { it.name.endsWith(".part") }

    @Test
    fun serverGuidIsReadableWithoutAuthentication() {
        val before = authLogLinesSettled()
        val info = probe().readServerInfo()
        println("[samba] server info: $info")
        // Samba derives the GUID from the NetBIOS name (container hostname kpm-nas).
        assertEquals(SambaContainer.sambaGuidFor("kpm-nas"), info.serverGuid)
        assertEquals("SMB 3.1.1", info.dialect)
        assertEquals(before, authLogLinesSettled(), "readServerInfo must not authenticate")
    }

    @Test
    fun guidMismatchAbortsBeforeAuthentication() {
        val user = "guid-probe-" + UUID.randomUUID().toString().take(8)
        val wrong = container.settings(password = "definitely-wrong".toCharArray(), username = user)
        val target = File(workDir, "mismatch.kdbx")
        val before = authLogLinesSettled()

        // Wrong GUID and wrong password: a GuidMismatch (not an auth failure) shows that
        // no authentication was attempted.
        val result = probe(wrong).download(UUID.randomUUID(), target)
        assertIs<SmbDownloadResult.GuidMismatch>(result)
        println("[samba] mismatch: expected=${result.expectedGuid} actual=${result.server.serverGuid} in ${result.totalMillis} ms")
        assertFalse(target.exists())
        assertTrue(leftoverParts().isEmpty())
        val after = authLogLinesSettled()
        assertEquals(before, after, "Samba must not have seen an authentication attempt")
        assertFalse(container.logs().contains(user), "Samba must not have seen the user name")

        // Positive control: same credentials without GUID check do reach Samba and are logged.
        val e = assertThrows<SmbProbeException> { probe(wrong).download(null, target) }
        assertEquals(SmbProbeException.Kind.AUTH_FAILED, e.kind)
        val control = authLogLinesSettled().filter { it.contains(user) }
        println("[samba] control auth log: $control")
        assertTrue(control.isNotEmpty(), "auth attempt must appear in the Samba log")
        assertFalse(target.exists())
    }

    @Test
    fun downloadIsByteIdenticalAndAtomic() {
        val expectedGuid = probe().readServerInfo().serverGuid
        val target = File(workDir, "smb-download.kdbx")
        repeat(2) { round ->
            // Round 2 replaces the existing target.
            val result = probe().download(expectedGuid, target)
            assertIs<SmbDownloadResult.Downloaded>(result)
            println("[samba] download round ${round + 1}: size=${result.size} modified=${result.lastModified} signed=${result.sessionSigned} encrypted=${result.sessionEncrypted} ${result.timings}")
            assertEquals(testFile.length(), result.size)
            assertContentEquals(sha256(testFile), sha256(target))
            val diff = Math.abs(result.lastModified.toEpochMilli() - testFile.lastModified())
            assertTrue(diff < 2_000, "lastModified differs by $diff ms")
            assertTrue(result.sessionSigned)
            assertTrue(leftoverParts().isEmpty())
        }
    }

    @Test
    fun signingAndEncryptionOptions() {
        for (encrypt in listOf(true, false)) {
            for (requireSigning in listOf(true, false)) {
                val base = container.settings()
                val settings = SmbSettings(
                    host = base.host, port = base.port, share = base.share, path = base.path,
                    username = base.username, password = base.password,
                    requireSigning = requireSigning, encryptIfOffered = encrypt,
                )
                val target = File(workDir, "options.kdbx")
                val info = probe(settings).readServerInfo()
                val result = probe(settings).download(info.serverGuid, target)
                assertIs<SmbDownloadResult.Downloaded>(result)
                println("[samba] encryptIfOffered=$encrypt requireSigning=$requireSigning -> dialect=${info.dialect} serverSigningRequired=${info.signingRequired} encryptionOffered=${info.encryptionOffered} cipher=${info.cipher} sessionSigned=${result.sessionSigned} sessionEncrypted=${result.sessionEncrypted}")
                assertContentEquals(sha256(testFile), sha256(target))
                assertEquals(encrypt, result.sessionEncrypted)
                assertTrue(result.sessionSigned)
            }
        }
    }

    @Test
    fun wrongCredentialsGiveCleanError() {
        val target = File(workDir, "wrong.kdbx")
        val badPassword = "wrong-password-123"
        val e1 = assertThrows<SmbProbeException> {
            probe(container.settings(password = badPassword.toCharArray())).download(null, target)
        }
        println("[samba] wrong password: ${e1.kind}: ${e1.message}")
        assertEquals(SmbProbeException.Kind.AUTH_FAILED, e1.kind)
        assertFalse(e1.message.orEmpty().contains(badPassword))

        val e2 = assertThrows<SmbProbeException> {
            probe(container.settings(username = "nobody")).download(null, target)
        }
        println("[samba] unknown user: ${e2.kind}: ${e2.message}")
        assertEquals(SmbProbeException.Kind.AUTH_FAILED, e2.kind)
        assertFalse(target.exists())
        assertTrue(leftoverParts().isEmpty())
    }

    @Test
    fun wrongShareOrPathGiveCleanError() {
        val target = File(workDir, "missing.kdbx")
        val e1 = assertThrows<SmbProbeException> { probe(container.settings(share = "nope")).download(null, target) }
        println("[samba] wrong share: ${e1.kind}: ${e1.message}")
        assertEquals(SmbProbeException.Kind.SHARE_NOT_FOUND, e1.kind)

        val e2 = assertThrows<SmbProbeException> { probe(container.settings(path = "missing.kdbx")).download(null, target) }
        println("[samba] wrong path: ${e2.kind}: ${e2.message}")
        assertEquals(SmbProbeException.Kind.FILE_NOT_FOUND, e2.kind)
        assertFalse(target.exists())
        assertTrue(leftoverParts().isEmpty())
    }

    @Test
    fun throughput() {
        val guid = probe().readServerInfo().serverGuid
        for ((file, encrypt) in listOf(testFile to true, bigFile to true, bigFile to false)) {
            val target = File(workDir, "speed-" + file.name)
            val base = container.settings(path = file.name)
            val settings = SmbSettings(
                host = base.host, port = base.port, share = base.share, path = base.path,
                username = base.username, password = base.password, encryptIfOffered = encrypt,
            )
            repeat(3) { round ->
                val result = probe(settings).download(guid, target)
                assertIs<SmbDownloadResult.Downloaded>(result)
                val t = result.timings
                println(
                    "[samba] ${file.name} (${file.length() / 1024} KiB) round ${round + 1}: connect=${t.connectMillis} auth=${t.authMillis} " +
                        "open=${t.openMillis} transfer=${t.transferMillis} total=${t.totalMillis} ms, " +
                        "%.1f MiB/s, encrypted=%s".format(t.throughputMiBPerSecond(result.size), result.sessionEncrypted),
                )
                assertContentEquals(sha256(file), sha256(target))
            }
        }
    }
}
