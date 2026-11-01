package de.lulurobb.keypassmobile.platform.smb

import java.util.UUID

/** Display names for server GUIDs (DS-04). */
object ServerNames {
    /**
     * Samba derives its server GUID from the NetBIOS name: the name as ASCII,
     * padded with zeros, in the byte order of an SMB GUID (first three fields
     * little-endian). Returns that name, e.g. "reitano-nas" for
     * 74696572-6e61-2d6f-6e61-730000000000, or null for a random GUID
     * (Windows) or anything that is not printable ASCII.
     */
    fun fromGuid(guid: UUID): String? {
        val bytes = wireBytes(guid)
        var end = bytes.size
        while (end > 0 && bytes[end - 1] == 0.toByte()) end--
        if (end == 0) return null
        val chars = CharArray(end)
        for (i in 0 until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x21 || b > 0x7E) return null
            chars[i] = b.toChar()
        }
        return String(chars)
    }

    /** The 16 bytes of [guid] as sent by the server (MS-DTYP GUID: Data1 to Data3 little-endian). */
    internal fun wireBytes(guid: UUID): ByteArray {
        val msb = guid.mostSignificantBits
        val lsb = guid.leastSignificantBits
        val out = ByteArray(16)
        // Data1 (bits 63..32 of msb), little-endian.
        for (i in 0 until 4) out[i] = (msb ushr (32 + 8 * i)).toByte()
        // Data2 (bits 31..16), little-endian.
        for (i in 0 until 2) out[4 + i] = (msb ushr (16 + 8 * i)).toByte()
        // Data3 (bits 15..0), little-endian.
        for (i in 0 until 2) out[6 + i] = (msb ushr (8 * i)).toByte()
        // Data4, big-endian as written.
        for (i in 0 until 8) out[8 + i] = (lsb ushr (56 - 8 * i)).toByte()
        return out
    }
}
