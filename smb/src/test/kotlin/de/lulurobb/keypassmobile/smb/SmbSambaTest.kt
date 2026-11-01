package de.lulurobb.keypassmobile.smb

import de.lulurobb.keypassmobile.smb.SmbException.Reason
import de.lulurobb.keypassmobile.smb.rpc.RpcTransport
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

/**
 * SmbConnector and SmbConnection against Samba 4.23 in Docker on host port 14450.
 * Skipped without Docker. The container is removed in [stopSamba], also on failure.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmbSambaTest {

    private lateinit var container: SambaContainer
    private lateinit var tempRoot: File
    private lateinit var workDir: File
    private lateinit var dbFile: File
    private lateinit var nestedFile: File
    private lateinit var guid: UUID

    private val fixedTime: Instant = Instant.parse("2026-10-01T12:00:00Z")
    private val connector = SmbConnector()

    @BeforeAll
    fun startSamba() {
        SambaContainer.assumeDocker()
        tempRoot = Files.createTempDirectory("kpm-smb-it").toFile()
        workDir = File(tempRoot, "work").apply { mkdirs() }
        val data = File(tempRoot, "data")
        val kpm = File(data, "kpm").apply { mkdirs() }
        val familie = File(data, "familie").apply { mkdirs() }
        File(familie, "urlaub.jpg").writeBytes(Random(1).nextBytes(1000))

        // Folder listing fixture: folders and files with umlauts and mixed case.
        for (folder in listOf("Zeitung", "Ärzte", "apfel", "Übersicht", "Bank")) File(kpm, folder).mkdirs()
        dbFile = File(kpm, "test.kdbx").apply { writeBytes(Random(42).nextBytes(2 * 1024 * 1024 + 17)) }
        for (name in listOf("Österreich.kdbx", "Archiv.KDBX", "alt.kdbx")) File(kpm, name).writeBytes(Random(3).nextBytes(100))
        for (name in listOf("notizen.txt", "test.kdbx.bak", "kdbx")) File(kpm, name).writeBytes(byteArrayOf(1, 2, 3))
        val praxis = File(kpm, "Ärzte/Praxis Müller").apply { mkdirs() }
        nestedFile = File(praxis, "2026.kdbx").apply { writeBytes(Random(5).nextBytes(12_345)) }
        kpm.walkTopDown().forEach { it.setLastModified(fixedTime.toEpochMilli()) }

        val smbConf = File(tempRoot, "smb.conf")
        smbConf.writeText(smbConfText(), Charsets.UTF_8)

        container = SambaContainer("kpm-smb-it", 14450, hostname = "kpm-nas", dataDir = data, smbConf = smbConf)
        container.start()
        guid = container.awaitReady()
    }

    @AfterAll
    fun stopSamba() {
        if (::container.isInitialized) container.remove()
        if (::tempRoot.isInitialized) tempRoot.deleteRecursively()
    }

    /**
     * Shares: kpm, Familie, a share with an umlaut and no comment, geheim (no access for the test user),
     * plus hidden shares that must be filtered: print$, versteckt$ and 80 filler shares
     * that make the RPC response span several fragments.
     */
    private fun smbConfText(): String = buildString {
        appendLine("[global]")
        appendLine("    server string = kpm test")
        appendLine("    security = user")
        appendLine("    server min protocol = SMB2")
        appendLine("    map to guest = Never")
        appendLine("    load printers = no")
        appendLine("    printing = bsd")
        appendLine("    printcap name = /dev/null")
        appendLine("    disable spoolss = yes")
        appendLine("    unix extensions = no")
        appendLine("    mangled names = no")
        appendLine("    unix charset = UTF-8")
        fun share(name: String, path: String, comment: String?, extra: String? = null) {
            appendLine("[$name]")
            appendLine("    path = $path")
            if (comment != null) appendLine("    comment = $comment")
            appendLine("    read only = yes")
            if (extra != null) appendLine("    $extra")
        }
        share("kpm", "/shared/kpm", "KeePass-Datenbank")
        share("Familie", "/shared/familie", "Fotos und Dokumente")
        share("Büro", "/shared/familie", null)
        share("geheim", "/shared/familie", "Kein Zugriff", "valid users = root")
        share("print$", "/shared/familie", "Druckertreiber")
        share("versteckt$", "/shared/familie", "Versteckte Freigabe")
        for (i in 0 until 80) share("zz%02d$".format(i), "/shared/familie", "Fuellfreigabe Nummer $i fuer mehrere RPC-Fragmente")
    }

    private fun connect(
        password: String = SambaContainer.PASSWORD,
        username: String = SambaContainer.USER,
        expectedGuid: UUID? = guid,
        options: SmbOptions = SmbOptions(),
    ): SmbConnection = SmbConnector(options).connect(container.server(username), password.toCharArray(), expectedGuid)

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

    @Test
    fun connectReadsGuidAndUsesSignedEncryptedSession() {
        assertEquals(SambaContainer.sambaGuidFor("kpm-nas"), guid)
        val c = connect(expectedGuid = null)
        c.use {
            println("[samba] guid=${it.serverGuid} dialect=${it.dialect} signed=${it.isSigned} encrypted=${it.isEncrypted}")
            assertEquals(guid, it.serverGuid)
            assertEquals("SMB 3.1.1", it.dialect)
            assertTrue(it.isEncrypted)
            assertTrue(it.isSigned)
        }
        // With the stored GUID the connection works as well.
        connect(expectedGuid = guid).use { assertEquals(guid, it.serverGuid) }
        // A closed connection rejects further calls with an SmbException.
        val e = assertThrows<SmbException> { c.listFolder("kpm", "") }
        assertEquals(Reason.OTHER, e.reason)
    }

    @Test
    fun guidMismatchAbortsBeforeAuthentication() {
        val user = "guid-probe-" + UUID.randomUUID().toString().take(8)
        val expected = UUID.randomUUID()
        val before = authLogLinesSettled()

        // Wrong GUID and wrong password: SERVER_CHANGED (not AUTH_FAILED) shows that no
        // authentication was attempted.
        val start = System.nanoTime()
        val e = assertThrows<SmbException> { connect(password = "definitely-wrong", username = user, expectedGuid = expected) }
        println("[samba] mismatch after ${(System.nanoTime() - start) / 1_000_000} ms: $e")
        assertEquals(Reason.SERVER_CHANGED, e.reason)
        assertEquals(guid, e.serverGuid)
        assertFalse(e.toString().contains("definitely-wrong"))
        assertEquals(before, authLogLinesSettled(), "Samba must not have seen an authentication attempt")
        assertFalse(container.logs().contains(user), "Samba must not have seen the user name")

        // Positive control: the same credentials without GUID check reach Samba and are logged.
        val control = assertThrows<SmbException> { connect(password = "definitely-wrong", username = user, expectedGuid = null) }
        assertEquals(Reason.AUTH_FAILED, control.reason)
        val logged = authLogLinesSettled().filter { it.contains(user) }
        println("[samba] control auth log: $logged")
        assertTrue(logged.isNotEmpty(), "auth attempt must appear in the Samba log")
    }

    @Test
    fun wrongPasswordOrUnknownUserGivesAuthFailed() {
        val badPassword = "wrong-password-123"
        val e1 = assertThrows<SmbException> { connect(password = badPassword) }
        println("[samba] wrong password: $e1")
        assertEquals(Reason.AUTH_FAILED, e1.reason)
        assertFalse(e1.message.orEmpty().contains(badPassword))
        assertFalse(e1.toString().contains(badPassword))

        val e2 = assertThrows<SmbException> { connect(username = "nobody") }
        println("[samba] unknown user: $e2")
        assertEquals(Reason.AUTH_FAILED, e2.reason)
    }

    @Test
    fun listSharesReturnsDiskSharesOnly() {
        val shares = connect().use { it.listShares() }
        println("[samba] shares: $shares")
        assertEquals(
            listOf(
                ShareInfo("Büro", ""),
                ShareInfo("Familie", "Fotos und Dokumente"),
                ShareInfo("geheim", "Kein Zugriff"),
                ShareInfo("kpm", "KeePass-Datenbank"),
            ),
            shares,
        )
    }

    @Test
    fun shareListingSpansSeveralRpcFragments() {
        var reads = 0
        val raw = connect().use { c ->
            enumerateShares(c.session) { inner ->
                object : RpcTransport by inner {
                    override fun read(maxLength: Int): ByteArray = inner.read(maxLength).also { reads++ }
                }
            }
        }
        println("[samba] raw shares: ${raw.size}, extra pipe reads: $reads, IPC$ type: ${raw.find { it.name == "IPC$" }?.type?.let { "0x%08X".format(it) }}")
        assertTrue(reads >= 1, "response should have needed more than one fragment")
        assertTrue(raw.any { it.name == "IPC$" })
        assertEquals(80, raw.count { it.name.startsWith("zz") })
        assertTrue(raw.any { it.name == "versteckt$" })
    }

    @Test
    fun listFolderFiltersAndSortsWithGermanCollation() {
        connect().use { c ->
            val root = c.listFolder("kpm", "")
            println("[samba] kpm root: ${root.map { (if (it.isFolder) "[d] " else "") + it.name }}")
            assertEquals(
                listOf("apfel", "Ärzte", "Bank", "Übersicht", "Zeitung", "alt.kdbx", "Archiv.KDBX", "Österreich.kdbx", "test.kdbx"),
                root.map { it.name },
            )
            assertEquals(listOf(true, true, true, true, true, false, false, false, false), root.map { it.isFolder })
            val test = root.single { it.name == "test.kdbx" }
            assertEquals(dbFile.length(), test.size)
            assertTrue(Math.abs(test.modified.toEpochMilli() - fixedTime.toEpochMilli()) < 2_000, "modified ${test.modified}")
            assertTrue(root.filter { it.isFolder }.all { it.size == 0L })

            assertEquals(root, c.listFolder("kpm", "/"))
            assertEquals(listOf("Praxis Müller"), c.listFolder("kpm", "Ärzte").map { it.name })
            assertEquals(
                listOf(FolderItem("2026.kdbx", false, nestedFile.length(), c.listFolder("kpm", "Ärzte\\Praxis Müller").single().modified)),
                c.listFolder("kpm", "/Ärzte/Praxis Müller/"),
            )
            assertEquals(emptyList(), c.listFolder("kpm", "apfel"))
            // Share names are case-insensitive.
            assertEquals(root, c.listFolder("KPM", ""))

            assertEquals(Reason.FILE_NOT_FOUND, assertThrows<SmbException> { c.listFolder("kpm", "fehlt") }.reason)
            assertEquals(Reason.FILE_NOT_FOUND, assertThrows<SmbException> { c.listFolder("kpm", "test.kdbx") }.reason)
            assertEquals(Reason.SHARE_NOT_FOUND, assertThrows<SmbException> { c.listFolder("gibtsnicht", "") }.reason)
            assertEquals(Reason.ACCESS_DENIED, assertThrows<SmbException> { c.listFolder("geheim", "") }.reason)
            // The connection stays usable after failures.
            assertEquals(root, c.listFolder("kpm", ""))
        }
    }

    @Test
    fun statAndDownload() {
        connect().use { c ->
            val stat = c.stat("kpm", "test.kdbx")
            assertEquals(dbFile.length(), stat.size)
            assertTrue(Math.abs(stat.modified.toEpochMilli() - fixedTime.toEpochMilli()) < 2_000, "modified ${stat.modified}")

            val target = File(workDir, "download.kdbx")
            repeat(2) { round ->
                // Round 2 overwrites the existing target.
                val start = System.nanoTime()
                val result = c.download("kpm", "test.kdbx", target)
                println("[samba] download round ${round + 1}: $result in ${(System.nanoTime() - start) / 1_000_000} ms")
                assertEquals(stat, result)
                assertEquals(dbFile.length(), target.length())
                assertContentEquals(sha256(dbFile), sha256(target))
            }

            val nestedTarget = File(workDir, "nested.kdbx")
            c.download("kpm", "Ärzte/Praxis Müller/2026.kdbx", nestedTarget)
            assertContentEquals(sha256(nestedFile), sha256(nestedTarget))

            // Missing file: FILE_NOT_FOUND and the target is neither created nor touched.
            val missingTarget = File(workDir, "missing.kdbx")
            val e1 = assertThrows<SmbException> { c.download("kpm", "fehlt.kdbx", missingTarget) }
            assertEquals(Reason.FILE_NOT_FOUND, e1.reason)
            assertFalse(missingTarget.exists())
            assertThrows<SmbException> { c.download("kpm", "fehlt.kdbx", target) }
            assertContentEquals(sha256(dbFile), sha256(target))

            assertEquals(Reason.FILE_NOT_FOUND, assertThrows<SmbException> { c.stat("kpm", "fehlt.kdbx") }.reason)
            assertEquals(Reason.FILE_NOT_FOUND, assertThrows<SmbException> { c.stat("kpm", "Ärzte") }.reason)
            assertEquals(Reason.FILE_NOT_FOUND, assertThrows<SmbException> { c.stat("kpm", "") }.reason)
            assertEquals(Reason.FILE_NOT_FOUND, assertThrows<SmbException> { c.download("kpm", "Ärzte", missingTarget) }.reason)
            assertFalse(missingTarget.exists())
            assertEquals(Reason.SHARE_NOT_FOUND, assertThrows<SmbException> { c.stat("gibtsnicht", "test.kdbx") }.reason)
            assertEquals(Reason.ACCESS_DENIED, assertThrows<SmbException> { c.stat("geheim", "urlaub.jpg") }.reason)
        }
    }

    @Test
    fun downloadFailsCleanlyIntoMissingFolder() {
        connect().use { c ->
            val target = File(workDir, "gibtsnicht/download.kdbx")
            val e = assertThrows<SmbException> { c.download("kpm", "test.kdbx", target) }
            assertEquals(Reason.OTHER, e.reason)
            assertFalse(target.exists())
        }
    }

    @Test
    fun worksWithoutEncryption() {
        connect(options = SmbOptions(encryptIfOffered = false)).use { c ->
            println("[samba] without encryption: dialect=${c.dialect} signed=${c.isSigned} encrypted=${c.isEncrypted}")
            assertFalse(c.isEncrypted)
            assertTrue(c.isSigned)
            val target = File(workDir, "plain.kdbx")
            c.download("kpm", "test.kdbx", target)
            assertContentEquals(sha256(dbFile), sha256(target))
            assertEquals(4, c.listShares().size)
        }
    }
}
