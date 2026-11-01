package de.lulurobb.keypassmobile.smb.rpc

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Failure of the DCE/RPC exchange: malformed or truncated data, bind rejected, RPC
 * fault or a Windows error code from the called function.
 *
 * @property accessDenied True if the server reported "access denied".
 */
internal class RpcException(message: String, val accessDenied: Boolean = false) : IOException(message)

/**
 * Minimal little-endian NDR 2.0 writer (C706 chapter 14). Primitives are aligned to
 * their natural size relative to the start of this writer.
 */
internal class NdrWriter {
    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun align(n: Int) {
        while (out.size() % n != 0) out.write(0)
    }

    fun u8(v: Int) {
        out.write(v and 0xFF)
    }

    fun u16(v: Int) {
        align(2)
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    fun u32(v: Long) {
        align(4)
        for (shift in 0 until 32 step 8) out.write(((v ushr shift) and 0xFF).toInt())
    }

    fun bytes(b: ByteArray) {
        out.write(b)
    }

    /** UUID in NDR layout: first three fields little-endian, the last 8 bytes as is. */
    fun uuid(u: UUID) {
        val msb = u.mostSignificantBits
        val lsb = u.leastSignificantBits
        u32((msb ushr 32) and 0xFFFFFFFFL)
        u16(((msb ushr 16) and 0xFFFF).toInt())
        u16((msb and 0xFFFF).toInt())
        for (shift in 56 downTo 0 step 8) out.write(((lsb ushr shift) and 0xFF).toInt())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * Minimal little-endian NDR 2.0 reader over `buf[start until end]`. Alignment is relative
 * to [start]. Every read is bounds-checked and throws [RpcException] when truncated.
 */
internal class NdrReader(
    private val buf: ByteArray,
    private val start: Int = 0,
    private val end: Int = buf.size,
) {
    var pos: Int = start
        private set

    val remaining: Int get() = end - pos

    fun align(n: Int) {
        val pad = (n - (pos - start) % n) % n
        skip(pad)
    }

    fun skip(n: Int) {
        need(n)
        pos += n
    }

    fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
        align(2)
        need(2)
        val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun u32(): Long {
        align(4)
        need(4)
        var v = 0L
        for (i in 0 until 4) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    /**
     * Conformant varying string of UTF-16 code units (`[string] wchar_t*` pointee):
     * max count, offset, actual count, then the characters. A trailing NUL is removed.
     */
    fun wideString(): String {
        val maxCount = u32()
        val offset = u32()
        val actual = u32()
        if (offset != 0L || actual > maxCount || actual * 2 > remaining) {
            throw RpcException("Malformed NDR string")
        }
        val bytes = actual.toInt() * 2
        val s = String(buf, pos, bytes, Charsets.UTF_16LE)
        pos += bytes
        return s.trimEnd('\u0000')
    }

    private fun need(n: Int) {
        if (n < 0 || pos + n > end) throw RpcException("Truncated RPC data")
    }
}
