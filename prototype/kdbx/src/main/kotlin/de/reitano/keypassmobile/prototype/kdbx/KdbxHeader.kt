package de.reitano.keypassmobile.prototype.kdbx

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Minimal reader for the unencrypted KDBX 4 header, only used to explain open errors. */
internal object KdbxHeader {
    val KDF_NAMES = mapOf(
        UUID.fromString("c9d9f39a-628a-4460-bf74-0d08c18a4fea") to "AES-KDF (KeePass-UUID)",
        // KeePassXC-internal id; KeePassXC itself writes the KeePass UUID above.
        UUID.fromString("7c02bb82-79a7-4ac0-927d-114a00648238") to "AES-KDF (KeePassXC-UUID 7c02bb82)",
        UUID.fromString("ef636ddf-8c29-444b-91f7-a9a403e30a0c") to "Argon2d",
        UUID.fromString("9e298b19-56db-4773-b23d-fc3ec6f0a1e6") to "Argon2id",
    )

    private const val FIELD_END = 0
    private const val FIELD_KDF_PARAMETERS = 11

    /** Returns the "\$UUID" entry of the KDF parameters, or null (KDBX 3.x or unreadable header). */
    fun kdfUuid(file: ByteArray): UUID? = try {
        readKdfUuid(ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN))
    } catch (_: BufferUnderflowException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun readKdfUuid(buf: ByteBuffer): UUID? {
        buf.position(10) // signatures (8 bytes) and minor version
        if (buf.short < 4) return null
        var id = buf.get().toInt()
        while (id != FIELD_END) {
            val size = buf.int
            if (id == FIELD_KDF_PARAMETERS) return readUuidItem(buf)
            buf.position(buf.position() + size)
            id = buf.get().toInt()
        }
        return null
    }

    /** Scans a VariantDictionary for the "\$UUID" item. */
    private fun readUuidItem(buf: ByteBuffer): UUID? {
        buf.short // dictionary version
        var type = buf.get().toInt()
        while (type != 0) {
            val key = ByteArray(buf.int).also { buf.get(it) }.toString(Charsets.UTF_8)
            val value = ByteArray(buf.int).also { buf.get(it) }
            if (key == "\$UUID" && value.size == 16) {
                val be = ByteBuffer.wrap(value)
                return UUID(be.long, be.long)
            }
            type = buf.get().toInt()
        }
        return null
    }
}
