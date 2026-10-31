package de.reitano.keypassmobile.prototype.smb

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Samba test server in Docker (image dockurr/samba, Samba 4.23). Test values only.
 * Driven through the docker CLI, so no extra test dependency is needed.
 */
class SambaContainer(
    val name: String,
    val hostPort: Int,
    /** Container hostname, which becomes the NetBIOS name. Null: Docker default (container id). */
    private val hostname: String? = "kpm-nas",
    /** Named volume for /var/lib/samba (Samba state, like on a real NAS). Null: none. */
    private val stateVolume: String? = null,
    /** Host directory mounted as the share root. Null: empty share. */
    private val shareDir: File? = null,
) {
    fun start() {
        remove()
        val args = mutableListOf("run", "-d", "--name", name, "-p", "$hostPort:445")
        if (hostname != null) args += listOf("--hostname", hostname)
        if (stateVolume != null) args += listOf("-v", "$stateVolume:/var/lib/samba")
        if (shareDir != null) args += listOf("--mount", "type=bind,source=${shareDir.absolutePath},target=/shared")
        args += listOf(
            "-e", "NAME=$SHARE",
            "-e", "USER=$USER",
            "-e", "PASS=$PASSWORD",
            "-e", "RW=false",
            // auth_audit:3 logs every authentication attempt with user name and status.
            "-e", "DEBUG_LEVEL=1 auth_audit:3",
            IMAGE,
        )
        docker(*args.toTypedArray(), timeoutSeconds = 180)
    }

    fun restart() {
        docker("restart", name)
    }

    /** -v also drops the anonymous /shared volume declared by the image; named volumes stay. */
    fun remove() {
        docker("rm", "-f", "-v", name, check = false)
    }

    fun logs(): String = docker("logs", name)

    /** Hostname inside the container (first 12 hex digits of the id when not set). */
    fun containerHostname(): String = docker("exec", name, "hostname").trim()

    fun settings(password: CharArray = PASSWORD.toCharArray(), username: String = USER, path: String = "test.kdbx", share: String = SHARE) =
        SmbSettings(
            host = "127.0.0.1",
            port = hostPort,
            share = share,
            path = path,
            username = username,
            password = password,
            domain = "",
        )

    /** Polls until smbd answers the NEGOTIATE request. */
    fun awaitReady(timeoutMillis: Long = 60_000): SmbServerInfo {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                return SmbProbe(settings()).readServerInfo()
            } catch (e: SmbProbeException) {
                last = e
                Thread.sleep(500)
            }
        }
        throw IllegalStateException("Samba container $name not ready", last)
    }

    companion object {
        const val IMAGE = "dockurr/samba:latest"
        const val SHARE = "kpm"
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
