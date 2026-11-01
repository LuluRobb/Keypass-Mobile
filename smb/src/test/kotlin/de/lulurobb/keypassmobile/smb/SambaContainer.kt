package de.lulurobb.keypassmobile.smb

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Samba test server in Docker (image dockurr/samba, Samba 4.23). Test values only.
 * Driven through the docker CLI, so no extra test dependency is needed.
 *
 * With [smbConf] the image uses that file as its complete configuration and only
 * creates the user [USER] with password [PASSWORD] from the environment.
 */
class SambaContainer(
    val name: String,
    val hostPort: Int,
    /** Container hostname, which becomes the NetBIOS name and thus the server GUID. */
    private val hostname: String = "kpm-nas",
    /** Host directory mounted at /shared. */
    private val dataDir: File,
    /** Host file mounted read-only as /etc/samba/smb.conf. */
    private val smbConf: File,
) {
    fun start() {
        remove()
        docker(
            "run", "-d", "--name", name, "-p", "127.0.0.1:$hostPort:445",
            "--hostname", hostname,
            "--mount", "type=bind,source=${dataDir.absolutePath},target=/shared",
            "--mount", "type=bind,source=${smbConf.absolutePath},target=/etc/samba/smb.conf,readonly",
            "-e", "USER=$USER",
            "-e", "PASS=$PASSWORD",
            // auth_audit:3 logs every authentication attempt with user name and status.
            "-e", "DEBUG_LEVEL=1 auth_audit:3",
            IMAGE,
            timeoutSeconds = 180,
        )
    }

    /** -v also drops the anonymous /shared volume declared by the image. */
    fun remove() {
        docker("rm", "-f", "-v", name, check = false)
    }

    fun logs(): String = docker("logs", name)

    fun server(username: String = USER) = SmbServer(host = "127.0.0.1", port = hostPort, username = username)

    /**
     * Polls until smbd answers the NEGOTIATE request. Uses a random expected GUID, so the
     * connector stops before authentication and the auth log stays clean.
     */
    fun awaitReady(timeoutMillis: Long = 60_000): UUID {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var last: Exception? = null
        val connector = SmbConnector(SmbOptions(connectTimeoutMillis = 1_000, responseTimeoutMillis = 2_000))
        while (System.currentTimeMillis() < deadline) {
            try {
                connector.connect(server("ready-probe"), "x".toCharArray(), UUID.randomUUID()).close()
                error("random GUID matched")
            } catch (e: SmbException) {
                if (e.reason == SmbException.Reason.SERVER_CHANGED) return e.serverGuid!!
                last = e
                Thread.sleep(500)
            }
        }
        throw IllegalStateException("Samba container $name not ready", last)
    }

    companion object {
        const val IMAGE = "dockurr/samba:latest"
        const val USER = "kpmtest"
        const val PASSWORD = "kpm-test-pass"

        val dockerAvailable: Boolean by lazy {
            try {
                val p = ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true).start()
                p.inputStream.readAllBytes()
                p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
            } catch (e: Exception) {
                false
            }
        }

        fun assumeDocker() = assumeTrue(dockerAvailable, "Docker not available, skipping Samba tests")

        fun docker(vararg args: String, check: Boolean = true, timeoutSeconds: Long = 120): String {
            val p = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                error("docker ${args.first()} timed out")
            }
            if (check && p.exitValue() != 0) error("docker ${args.joinToString(" ")} failed: $out")
            return out
        }

        /**
         * GUID that Samba sends: the lower-case NetBIOS name as ASCII, NUL-padded to
         * 16 bytes, read as an MS-DTYP GUID (see negprot_spnego in smb2_negprot.c).
         */
        fun sambaGuidFor(netbiosName: String): UUID {
            val bytes = netbiosName.lowercase().take(15).toByteArray(Charsets.US_ASCII).copyOf(16)
            val le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val data1 = le.getInt(0).toLong() and 0xFFFFFFFFL
            val data2 = le.getShort(4).toLong() and 0xFFFFL
            val data3 = le.getShort(6).toLong() and 0xFFFFL
            val data4 = ByteBuffer.wrap(bytes, 8, 8).order(ByteOrder.BIG_ENDIAN).getLong()
            return UUID((data1 shl 32) or (data2 shl 16) or data3, data4)
        }
    }
}
