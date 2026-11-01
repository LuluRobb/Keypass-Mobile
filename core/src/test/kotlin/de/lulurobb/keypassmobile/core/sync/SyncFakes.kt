package de.lulurobb.keypassmobile.core.sync

import de.lulurobb.keypassmobile.core.NoSecrets
import de.lulurobb.keypassmobile.model.Vault
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake KDBX format: "KDBX|<key>|<label>". The file opens with the password
 * hash bytes of <key>; the vault's root group name is <label>, so tests see
 * which version was opened. Anything else starting with "KDBX|" is corrupt,
 * anything else is no KDBX at all.
 */
fun kdbx(key: String, label: String) = "KDBX|$key|$label"

fun key(text: String): ByteArray = text.toByteArray()

class FakeOpener : VaultOpener {
    val opens = AtomicInteger()

    /** Secrets of every vault handed out, to check that discarded vaults get closed. */
    val secrets: MutableList<NoSecrets> = CopyOnWriteArrayList()

    /** Called before each open, e.g. to block in concurrency tests. */
    @Volatile
    var beforeOpen: ((File) -> Unit)? = null

    override fun open(file: File, passwordHash: ByteArray): Vault {
        beforeOpen?.invoke(file)
        opens.incrementAndGet()
        val parts = file.readText().split('|')
        if (parts.size != 3 || parts[0] != "KDBX") throw VaultOpenException(OpenFailure.CORRUPT)
        if (parts[1] != String(passwordHash)) throw VaultOpenException(OpenFailure.WRONG_KEY)
        val vaultSecrets = NoSecrets()
        secrets += vaultSecrets
        return Vault(parts[2], emptyList(), emptyMap(), vaultSecrets)
    }

    override fun looksLikeKdbx(file: File): Boolean = file.readText().startsWith("KDBX|")
}

class FakeRemote(content: String, modified: Instant) : RemoteSource {
    @Volatile
    var content: String = content

    @Volatile
    var modified: Instant = modified

    @Volatile
    var statError: Exception? = null

    @Volatile
    var downloadError: Exception? = null

    /** Writes only this many bytes, then throws [downloadError] (or returns normally without one). */
    @Volatile
    var partialBytes: Int? = null

    /** Called at the start of each download, e.g. to block in concurrency tests. */
    @Volatile
    var onDownload: (() -> Unit)? = null

    val stats = AtomicInteger()
    val downloads = AtomicInteger()

    fun publish(content: String, modified: Instant) {
        this.content = content
        this.modified = modified
    }

    override fun stat(): RemoteStat {
        stats.incrementAndGet()
        statError?.let { throw it }
        return RemoteStat(content.toByteArray().size.toLong(), modified)
    }

    override fun download(target: File) {
        downloads.incrementAndGet()
        onDownload?.invoke()
        val bytes = content.toByteArray()
        val partial = partialBytes
        if (partial != null) {
            target.writeBytes(bytes.copyOf(partial))
            downloadError?.let { throw it }
            return
        }
        downloadError?.let { throw it }
        target.writeBytes(bytes)
    }
}
