package de.lulurobb.keypassmobile.kdbx

import java.io.File
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Returns true if [file] starts with the KDBX signatures (KeePass 2.x,
 * any KDBX version). Reads only the first bytes and needs no key. Meant as
 * sanity check for a downloaded file (e.g. the background sync, SY-06): it
 * catches HTML error pages, empty or foreign files, not damaged content.
 * Returns false if the file cannot be read.
 */
fun looksLikeKdbx(file: File): Boolean {
    val start = ByteArray(KdbxHeader.SIGNATURE_LENGTH)
    return try {
        file.inputStream().use { input ->
            var read = 0
            while (read < start.size) {
                val n = input.read(start, read, start.size - read)
                if (n < 0) break
                read += n
            }
            read == start.size && KdbxHeader.inspect(start) is KdbxHeader.Inspection.Kdbx
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/**
 * Minimal reader for the unencrypted outer KDBX header (3.x and 4.x). Only
 * used to check signatures and to explain why kotpass could not open a file.
 * Never needed for a successful open.
 */
internal object KdbxHeader {
    /** Two signatures (4 bytes each) plus minor and major version (2 bytes each). */
    const val SIGNATURE_LENGTH = 12

    private const val SIGNATURE_1 = 0x9AA2D903.toInt()
    private const val SIGNATURE_2_KDBX = 0xB54BFB67.toInt()
    private const val SIGNATURE_2_KDB = 0xB54BFB65.toInt() // KeePass 1.x
    private const val SIGNATURE_2_PRE_RELEASE = 0xB54BFB66.toInt() // KeePass 2.x pre-release

    private const val FIELD_END = 0
    private const val FIELD_CIPHER_ID = 2
    private const val FIELD_KDF_PARAMETERS = 11

    val CIPHER_AES256: UUID = UUID.fromString("31c1f2e6-bf71-4350-be58-05216afc5aff")
    val CIPHER_CHACHA20: UUID = UUID.fromString("d6038a2b-8b6f-4cb5-a524-339a31dbb59a")
    val CIPHER_TWOFISH: UUID = UUID.fromString("ad68f29f-576f-4bb9-a36a-d47af965346c")
    val KDF_AES: UUID = UUID.fromString("c9d9f39a-628a-4460-bf74-0d08c18a4fea")
    val KDF_ARGON2D: UUID = UUID.fromString("ef636ddf-8c29-444b-91f7-a9a403e30a0c")
    val KDF_ARGON2ID: UUID = UUID.fromString("9e298b19-56db-4773-b23d-fc3ec6f0a1e6")

    /** Ciphers and KDFs that kotpass 0.13.0 implements. */
    private val KNOWN_CIPHERS = setOf(CIPHER_AES256, CIPHER_CHACHA20, CIPHER_TWOFISH)
    private val KNOWN_KDFS = setOf(KDF_AES, KDF_ARGON2D, KDF_ARGON2ID)

    sealed interface Inspection {
        /** Wrong first signature or too short: not a KeePass file at all. */
        data object NotKeePass : Inspection

        /** KeePass 1.x (.kdb) or a KeePass 2 pre-release format. */
        class OtherKeePass(val description: String) : Inspection

        /**
         * KDBX file. Ids are null if the header ends early or cannot be parsed.
         * [headerLength] is the offset of the payload, null if the end of the
         * header was not found.
         */
        class Kdbx(
            val major: Int,
            val minor: Int,
            val cipherId: UUID?,
            val kdfId: UUID?,
            val headerLength: Int?,
        ) : Inspection
    }

    fun inspect(bytes: ByteArray): Inspection {
        if (bytes.size < SIGNATURE_LENGTH) return Inspection.NotKeePass
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != SIGNATURE_1) return Inspection.NotKeePass
        when (buf.int) {
            SIGNATURE_2_KDBX -> Unit
            SIGNATURE_2_KDB -> return Inspection.OtherKeePass("KeePass 1 (KDB)")
            SIGNATURE_2_PRE_RELEASE -> return Inspection.OtherKeePass("KeePass-2-Vorabversion")
            else -> return Inspection.NotKeePass
        }
        val minor = buf.short.toInt() and 0xFFFF
        val major = buf.short.toInt() and 0xFFFF
        var cipherId: UUID? = null
        var kdfId: UUID? = null
        var headerLength: Int? = null
        try {
            // Field: id (1 byte), size (2 bytes in 3.x, 4 bytes in 4.x), data.
            // The end-of-header field also carries a size and data.
            val sizeBytes = if (major >= 4) 4 else 2
            while (headerLength == null) {
                val id = buf.get().toInt()
                val size = if (sizeBytes == 4) buf.int else buf.short.toInt() and 0xFFFF
                require(size >= 0 && size <= buf.remaining())
                val end = buf.position() + size
                when (id) {
                    FIELD_END -> headerLength = end
                    FIELD_CIPHER_ID -> if (size == 16) cipherId = readUuid(buf)
                    FIELD_KDF_PARAMETERS -> {
                        val dictionary = buf.slice().order(ByteOrder.LITTLE_ENDIAN)
                        dictionary.limit(size)
                        kdfId = readKdfUuid(dictionary)
                    }
                }
                buf.position(end)
            }
        } catch (_: BufferUnderflowException) {
            // Header ends early: report what was found so far.
        } catch (_: IllegalArgumentException) {
            // Invalid size: same.
        } catch (_: IndexOutOfBoundsException) {
            // Same.
        }
        return Inspection.Kdbx(major, minor, cipherId, kdfId, headerLength)
    }

    /**
     * True if a KDBX 3.x payload cannot be complete. KDBX 3.x has no header
     * MAC and kotpass decrypts the whole payload before it checks the key, so
     * a truncated file looks like a wrong key. With a 16-byte block cipher
     * the payload length must be a positive multiple of 16; a truncation
     * that happens to keep that is still reported as wrong key.
     */
    fun isTruncatedKdbx3(inspection: Inspection, fileLength: Int): Boolean {
        if (inspection !is Inspection.Kdbx || inspection.major >= 4) return false
        val headerLength = inspection.headerLength ?: return true
        if (inspection.cipherId != CIPHER_AES256 && inspection.cipherId != CIPHER_TWOFISH) return false
        val payload = fileLength - headerLength
        return payload <= 0 || payload % 16 != 0
    }

    /**
     * German description of why the file is not supported, or null if the
     * header looks supported (or cannot tell).
     */
    fun unsupportedDetail(inspection: Inspection): String? = when (inspection) {
        Inspection.NotKeePass -> null
        is Inspection.OtherKeePass -> inspection.description
        is Inspection.Kdbx -> when {
            inspection.major !in 3..4 -> "KDBX ${inspection.major}.${inspection.minor}"
            inspection.cipherId != null && inspection.cipherId !in KNOWN_CIPHERS ->
                "Verschlüsselung ${inspection.cipherId}"
            inspection.major >= 4 && inspection.kdfId != null && inspection.kdfId !in KNOWN_KDFS ->
                "Schlüsselableitung ${inspection.kdfId}"
            else -> null
        }
    }

    /** UUIDs are stored as 16 raw bytes in big-endian order. */
    private fun readUuid(buf: ByteBuffer): UUID {
        val bytes = ByteArray(16).also { buf.get(it) }
        val be = ByteBuffer.wrap(bytes)
        return UUID(be.long, be.long)
    }

    /** Scans a VariantDictionary for the "\$UUID" item. */
    private fun readKdfUuid(buf: ByteBuffer): UUID? {
        buf.short // dictionary version
        var type = buf.get().toInt()
        while (type != 0) {
            val keyLength = buf.int
            require(keyLength in 0..buf.remaining())
            val key = ByteArray(keyLength).also { buf.get(it) }.toString(Charsets.UTF_8)
            val valueLength = buf.int
            require(valueLength in 0..buf.remaining())
            val value = ByteArray(valueLength).also { buf.get(it) }
            if (key == "\$UUID" && value.size == 16) return readUuid(ByteBuffer.wrap(value))
            type = buf.get().toInt()
        }
        return null
    }
}
