package de.reitano.keypassmobile.prototype.kdbx

import java.util.Locale
import java.util.UUID

sealed interface KdfInfo {
    fun describe(): String

    data class Argon2(
        val variant: Argon2Variant,
        val memoryKiB: Long,
        val iterations: Long,
        val parallelism: Int,
        val version: Int,
    ) : KdfInfo {
        override fun describe(): String =
            "$variant, Speicher ${formatMemory(memoryKiB)}, Iterationen $iterations, " +
                "Parallelität $parallelism, Version 0x${version.toString(16)}"
    }

    data class AesKdf(val rounds: Long) : KdfInfo {
        override fun describe(): String = "AES-KDF, Runden $rounds"
    }
}

private fun formatMemory(kiB: Long): String =
    if (kiB % 1024 == 0L) "${kiB / 1024} MiB" else "$kiB KiB"

/** Result of one unlock. All times in milliseconds. */
class OpenStats(
    /** KDBX version from the outer header, e.g. "4.1". */
    val formatVersion: String,
    val cipher: String,
    val kdf: KdfInfo,
    val kdfImplementation: String,
    /** Key derivation only (Argon2 or AES-KDF). */
    val kdfMillis: Double,
    /** Complete kotpass decode: KDF, decryption, decompression, XML parsing. */
    val decodeMillis: Double,
    /** Building the in-memory entry list used for searching. */
    val indexMillis: Double,
    val entryCount: Int,
    /** Includes the root group, like KeePassXC's db-info. */
    val groupCount: Int,
) {
    val totalMillis: Double get() = decodeMillis + indexMillis
}

/**
 * Searchable view of one entry. Holds no password and no protected field
 * values. Plain strings cannot be wiped, so drop all references on lock.
 */
class EntrySummary(
    val uuid: UUID,
    val title: String,
    val userName: String,
    val url: String,
    /** KeePassXC "additional URLs": attributes KP2A_URL, KP2A_URL_1, ... */
    val additionalUrls: List<String>,
    val notes: String,
    val tags: List<String>,
    /** Unprotected custom attributes (without KP2A_URL*). */
    val customFields: Map<String, String>,
    /** Names of protected custom attributes; values are not copied. */
    val protectedFieldNames: Set<String>,
) {
    internal val searchText: String =
        (listOf(title, userName, url, notes) + additionalUrls + tags)
            .joinToString("\n")
            .lowercase(Locale.ROOT)
}

class SearchResult(val matches: List<EntrySummary>, val nanos: Long) {
    val millis: Double get() = nanos / 1_000_000.0
}

class SearchTiming(val query: String, val hits: Int, val firstMillis: Double, val averageMillis: Double)

class KdbxOpenException(val reason: Reason, message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    enum class Reason { WRONG_KEY, UNSUPPORTED, CORRUPT, OTHER }
}
