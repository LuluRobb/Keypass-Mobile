package de.lulurobb.keypassmobile.platform.session

import de.lulurobb.keypassmobile.core.sync.OpenFailure
import de.lulurobb.keypassmobile.core.sync.RemoteStat
import de.lulurobb.keypassmobile.core.sync.VaultOpenException
import de.lulurobb.keypassmobile.core.sync.VaultOpener
import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.model.VaultSecrets
import de.lulurobb.keypassmobile.platform.keystore.HashStore
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fake KDBX format "KDBX|<key>|<label>" as in the core tests: the file opens
 * with the hash bytes of <key>; the vault's root group name is <label>.
 */
fun kdbx(key: String, label: String) = "KDBX|$key|$label"

class TestSecrets : VaultSecrets {
    @Volatile
    var closed = false
        private set

    override fun password(entryId: UUID): CharArray = CharArray(0)

    override fun close() {
        closed = true
    }
}

class FakeOpener : VaultOpener {
    val secrets: MutableList<TestSecrets> = CopyOnWriteArrayList()

    /** Every hash array passed to open, by reference, to check wiping. */
    val hashes: MutableList<ByteArray> = CopyOnWriteArrayList()

    @Volatile
    var beforeOpen: ((File) -> Unit)? = null

    override fun open(file: File, passwordHash: ByteArray): Vault {
        hashes += passwordHash
        beforeOpen?.invoke(file)
        val parts = file.readText().split('|')
        if (parts.size != 3 || parts[0] != "KDBX") throw VaultOpenException(OpenFailure.CORRUPT)
        if (parts[1] != String(passwordHash)) throw VaultOpenException(OpenFailure.WRONG_KEY)
        val s = TestSecrets()
        secrets += s
        return Vault(parts[2], emptyList(), emptyMap(), s)
    }

    override fun looksLikeKdbx(file: File): Boolean = file.readText().startsWith("KDBX|")
}

class FakeRemote(@Volatile var content: String, @Volatile var modified: Instant) : RemoteHandle {
    @Volatile
    var statError: Exception? = null

    @Volatile
    var onDownload: (() -> Unit)? = null

    @Volatile
    var closed = 0

    fun publish(content: String, modified: Instant) {
        this.content = content
        this.modified = modified
    }

    override fun stat(): RemoteStat {
        statError?.let { throw it }
        return RemoteStat(content.toByteArray().size.toLong(), modified)
    }

    override fun download(target: File) {
        onDownload?.invoke()
        target.writeBytes(content.toByteArray())
    }

    override fun close() {
        closed++
    }
}

class FakeHashStore : HashStore {
    @Volatile
    var stored: ByteArray? = null

    @Volatile
    var validityEnd: Instant? = null

    @Volatile
    var failStore = false

    /** Called at the start of every store, e.g. to lock the session while the unlock waits for it. */
    @Volatile
    var onStore: (() -> Unit)? = null

    /** Called at the start of every clear, e.g. to simulate a typed password arriving at the same time. */
    @Volatile
    var onClear: (() -> Unit)? = null

    @Volatile
    var clears = 0

    override fun hasStoredValue(): Boolean = stored != null

    override fun store(hash: ByteArray, validityEnd: Instant) {
        onStore?.invoke()
        if (failStore) throw IllegalStateException("no fingerprint enrolled")
        stored = hash.copyOf()
        this.validityEnd = validityEnd
    }

    override fun clear() {
        onClear?.invoke()
        stored = null
        validityEnd = null
        clears++
    }
}

class RecordingEffects : SessionEffects {
    @Volatile
    var unlocked = 0

    val locks: MutableList<LockReason> = CopyOnWriteArrayList()

    override fun onUnlocked() {
        unlocked++
    }

    override fun onLocked(reason: LockReason) {
        locks += reason
    }
}

class MutableClock(@Volatile var now: Instant) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

/** In-memory [SwitchJournal] that remembers every write. */
internal class MemoryJournal(var record: SwitchRecord? = null) : SwitchJournal {
    val writes = mutableListOf<SwitchRecord?>()

    override fun read(): SwitchRecord? = record

    override fun write(record: SwitchRecord?) {
        this.record = record
        writes += record
    }
}
