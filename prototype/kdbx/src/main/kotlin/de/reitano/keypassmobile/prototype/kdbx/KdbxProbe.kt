package de.reitano.keypassmobile.prototype.kdbx

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Prototype 2: open a KDBX file with kotpass, measure the unlock and build
 * the in-memory entry list for searching (UI-01). Read-only.
 */
object KdbxProbe {
    const val LIBRARY = "kotpass 0.13.0"

    /**
     * Opens [fileBytes] with the master password (UTF-8 bytes, like KeePassXC)
     * through kotpass' regular passphrase API. [password] is overwritten.
     */
    fun open(fileBytes: ByteArray, password: ByteArray, argon2: Argon2Function? = null): OpenedDatabase {
        try {
            // fromBinary masks the array in place and keeps it; Credentials.from
            // stores SHA-256 of it in a new value, so the array is not needed after this.
            val credentials = Credentials.from(EncryptedValue.fromBinary(password))
            return decode(fileBytes, credentials, argon2)
        } finally {
            password.fill(0)
        }
    }

    /**
     * Opens [fileBytes] with SHA-256(password), the password component of the
     * composite key (EN-03). kotpass has no API for that, but a 32-byte key file
     * is used as-is as key component, and with a single component the composite
     * key is SHA-256(component) in both cases. Only valid for databases that are
     * protected by a password alone. [passwordHash] is overwritten.
     */
    fun openWithPasswordHash(
        fileBytes: ByteArray,
        passwordHash: ByteArray,
        argon2: Argon2Function? = null,
    ): OpenedDatabase {
        require(passwordHash.size == 32) { "SHA-256-Hash mit 32 Byte erwartet" }
        try {
            return decode(fileBytes, Credentials.from(passwordHash.copyOf()), argon2)
        } finally {
            passwordHash.fill(0)
        }
    }

    /** SHA-256 of the UTF-8 password, the value EN-03 stores. Leaves [password] unchanged. */
    fun passwordHash(password: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(password)

    private val CIPHERS = BaseCiphers.entries + TwofishCipher

    private val CIPHER_NAMES = mapOf(
        UUID.fromString("31c1f2e6-bf71-4350-be58-05216afc5aff") to "AES-256",
        UUID.fromString("d6038a2b-8b6f-4cb5-a524-339a31dbb59a") to "ChaCha20",
        UUID.fromString("ad68f29f-576f-4bb9-a36a-d47af965346c") to "Twofish",
    )

    private fun decode(fileBytes: ByteArray, credentials: Credentials, argon2: Argon2Function?): OpenedDatabase {
        val kdf = MeasuringKdfProvider(argon2)
        val start = System.nanoTime()
        val database = try {
            KeePassDatabase.decode(
                inputStream = ByteArrayInputStream(fileBytes),
                credentials = credentials,
                cipherProviders = CIPHERS,
                kdfProvider = kdf,
            )
        } catch (e: Exception) {
            throw translate(e, fileBytes)
        }
        val decodeNanos = System.nanoTime() - start

        val indexStart = System.nanoTime()
        val entries = ArrayList<EntrySummary>()
        val groupCount = collect(database.content.group, entries)
        val indexNanos = System.nanoTime() - indexStart

        val header = database.header
        val stats = OpenStats(
            formatVersion = "${header.version.major}.${header.version.minor}",
            cipher = CIPHER_NAMES[header.cipherId] ?: header.cipherId.toString(),
            kdf = kdf.info ?: error("KDF wurde nicht aufgerufen"),
            kdfImplementation = kdf.implementation,
            kdfMillis = kdf.nanos / 1_000_000.0,
            decodeMillis = decodeNanos / 1_000_000.0,
            indexMillis = indexNanos / 1_000_000.0,
            entryCount = entries.size,
            groupCount = groupCount,
        )
        return OpenedDatabase(database, stats, entries)
    }

    private fun translate(e: Exception, fileBytes: ByteArray): KdbxOpenException = when (e) {
        is CryptoError.InvalidKey ->
            KdbxOpenException(KdbxOpenException.Reason.WRONG_KEY, "Falsches Passwort (oder falscher Schlüssel).", e)
        is FormatError.UnknownFormat, is FormatError.UnsupportedVersion ->
            KdbxOpenException(KdbxOpenException.Reason.UNSUPPORTED, "Format nicht unterstützt: ${e.message}", e)
        is FormatError ->
            KdbxOpenException(KdbxOpenException.Reason.CORRUPT, "Datei fehlerhaft: ${e.message}", e)
        is NoSuchElementException -> {
            // kotpass throws this for a KDF UUID it does not know.
            val kdfUuid = KdbxHeader.kdfUuid(fileBytes)
            val name = KdbxHeader.KDF_NAMES[kdfUuid] ?: kdfUuid?.toString() ?: "unbekannt"
            KdbxOpenException(KdbxOpenException.Reason.UNSUPPORTED, "KDF wird von kotpass nicht unterstützt: $name", e)
        }
        else -> KdbxOpenException(KdbxOpenException.Reason.OTHER, "${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** Collects entries (without history) depth-first and returns the number of groups. */
    private fun collect(group: Group, out: MutableList<EntrySummary>): Int {
        group.entries.mapTo(out) { it.toSummary() }
        return 1 + group.groups.sumOf { collect(it, out) }
    }

    private val BASIC_KEYS = BasicField.entries.map { it.key }.toSet()

    private fun isAdditionalUrl(key: String) = key == "KP2A_URL" || key.startsWith("KP2A_URL_")

    private fun Entry.toSummary(): EntrySummary {
        val custom = fields.filterKeys { it !in BASIC_KEYS && !isAdditionalUrl(it) }
        return EntrySummary(
            uuid = uuid,
            title = fields.title?.content.orEmpty(),
            userName = fields.userName?.content.orEmpty(),
            url = fields.url?.content.orEmpty(),
            additionalUrls = fields.keys.filter(::isAdditionalUrl)
                .sortedWith(compareBy({ it.length }, { it }))
                .mapNotNull { fields[it]?.content?.takeIf(String::isNotBlank) },
            notes = fields.notes?.content.orEmpty(),
            tags = tags,
            customFields = custom.entries.filter { it.value is EntryValue.Plain }.associate { it.key to it.value.content },
            protectedFieldNames = custom.entries.filter { it.value is EntryValue.Encrypted }.map { it.key }.toSet(),
        )
    }
}

/**
 * Unlocked database for the probe. Keeps the kotpass model (protected values
 * stay XOR-masked by kotpass) and the search list. Call [close] to drop both.
 */
class OpenedDatabase internal constructor(
    database: KeePassDatabase,
    val stats: OpenStats,
    entries: List<EntrySummary>,
) {
    private var database: KeePassDatabase? = database

    var entries: List<EntrySummary> = entries
        private set

    /** Case-insensitive substring search; every whitespace-separated term must match. */
    fun search(query: String): SearchResult {
        val start = System.nanoTime()
        val terms = query.lowercase(Locale.ROOT).split(WHITESPACE).filter { it.isNotEmpty() }
        val matches = if (terms.isEmpty()) {
            entries
        } else {
            entries.filter { entry -> terms.all { entry.searchText.contains(it) } }
        }
        return SearchResult(matches, System.nanoTime() - start)
    }

    /** Runs [search] [repetitions] times; the first run includes JIT warm-up. */
    fun measureSearch(query: String, repetitions: Int = 5): SearchTiming {
        val runs = (1..repetitions).map { search(query) }
        return SearchTiming(
            query = query,
            hits = runs.first().matches.size,
            firstMillis = runs.first().millis,
            averageMillis = runs.map { it.millis }.average(),
        )
    }

    /** For tests: decrypts the password of one entry on demand. */
    internal fun passwordOf(uuid: UUID): String? {
        var result: String? = null
        database?.content?.group?.traverse { element ->
            if (element is Entry && element.uuid == uuid) result = element.fields.password?.content
        }
        return result
    }

    fun close() {
        database = null
        entries = emptyList()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
