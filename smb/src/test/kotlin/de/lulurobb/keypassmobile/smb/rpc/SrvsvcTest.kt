package de.lulurobb.keypassmobile.smb.rpc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows

/** DCE/RPC framing and NDR coding of NetShareEnumAll, against a scripted fake server. */
class SrvsvcTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun pdu(ptype: Int, flags: Int, callId: Long, body: ByteArray): ByteArray = NdrWriter().apply {
        u8(5); u8(0); u8(ptype); u8(flags)
        bytes(byteArrayOf(0x10, 0, 0, 0))
        u16(16 + body.size); u16(0); u32(callId)
        bytes(body)
    }.toByteArray()

    private fun bindAck(callId: Long = 1, result: Int = 0, maxXmit: Int = 4280): ByteArray {
        // Built as a whole PDU so that NDR alignment is relative to the PDU start.
        val w = NdrWriter()
        w.u8(5); w.u8(0); w.u8(12); w.u8(3)
        w.bytes(byteArrayOf(0x10, 0, 0, 0))
        w.u16(0); w.u16(0); w.u32(callId) // frag_length patched below
        w.u16(maxXmit); w.u16(4280); w.u32(0x1234)
        val port = "\\PIPE\\srvsvc\u0000".toByteArray(Charsets.US_ASCII)
        w.u16(port.size); w.bytes(port)
        w.align(4)
        w.u8(1); w.u8(0); w.u16(0)
        w.u16(result); w.u16(if (result == 0) 0 else 2)
        w.bytes(ByteArray(20))
        val b = w.toByteArray()
        b[8] = (b.size and 0xFF).toByte()
        b[9] = (b.size ushr 8).toByte()
        return b
    }

    private fun NdrWriter.wideString(s: String) {
        val z = s + "\u0000"
        u32(z.length.toLong()); u32(0); u32(z.length.toLong())
        z.forEach { u16(it.code) }
    }

    private fun responseStub(shares: List<RawShare>, werror: Long = 0, nullComment: Boolean = false): ByteArray = NdrWriter().apply {
        u32(1); u32(1); u32(0x20000)
        u32(shares.size.toLong()); u32(if (shares.isEmpty()) 0 else 0x20004)
        if (shares.isNotEmpty()) {
            u32(shares.size.toLong())
            shares.forEachIndexed { i, s ->
                u32(0x20008L + 8 * i)
                u32(s.type)
                u32(if (nullComment) 0 else 0x2000CL + 8 * i)
            }
            shares.forEach {
                wideString(it.name)
                if (!nullComment) wideString(it.comment)
            }
        }
        u32(shares.size.toLong())
        u32(0x30000); u32(0) // resume handle
        u32(werror)
    }.toByteArray()

    private fun responseFragments(stub: ByteArray, callId: Long = 2, chunk: Int = 4256): List<ByteArray> {
        val parts = stub.toList().chunked(chunk).map { it.toByteArray() }.ifEmpty { listOf(ByteArray(0)) }
        return parts.mapIndexed { i, part ->
            val flags = (if (i == 0) 1 else 0) or (if (i == parts.lastIndex) 2 else 0)
            val body = NdrWriter().apply {
                u32(stub.size.toLong()); u16(0); u8(0); u8(0)
                bytes(part)
            }.toByteArray()
            pdu(2, flags, callId, body)
        }
    }

    /**
     * Fake pipe: answers BIND with [bindReply] and REQUEST with the first element of
     * [replies]; the rest is returned by [read], optionally split into small pieces.
     */
    private class FakePipe(
        private val bindReply: ByteArray,
        replies: List<ByteArray>,
        private val readPiece: Int = Int.MAX_VALUE,
    ) : RpcTransport {
        val requests = mutableListOf<ByteArray>()
        private val pending = ArrayDeque(replies)
        private var current = ByteArray(0)
        var reads = 0

        override fun transact(pdu: ByteArray): ByteArray {
            requests += pdu
            return if (pdu[2].toInt() == 11) bindReply else pending.removeFirst()
        }

        override fun read(maxLength: Int): ByteArray {
            reads++
            if (current.isEmpty()) current = pending.removeFirstOrNull() ?: return ByteArray(0)
            val n = minOf(readPiece, maxLength, current.size)
            return current.copyOfRange(0, n).also { current = current.copyOfRange(n, current.size) }
        }
    }

    private val sample = listOf(
        RawShare("kpm", 0, "KeePass-Datenbank"),
        RawShare("IPC$", 0x80000003L, "IPC Service (Samba)"),
        RawShare("Büro", 0, ""),
    )

    @Test
    fun bindPduMatchesReferenceBytes() {
        val expected = hex(
            "05000b03 10000000 4800 0000 01000000" + // header, frag_length 72, call id 1
                "b810 b810 00000000" + // max_xmit 4280, max_recv 4280, assoc group 0
                "01 00 0000 0000 01 00" + // one context, id 0, one transfer syntax
                "c84f324b7016d30112785a47bf6ee188 0300 0000" + // SRVSVC v3.0
                "045d888aeb1cc9119fe808002b104860 02000000", // NDR v2
        )
        assertContentEquals(expected, Srvsvc.bindPdu(1))
    }

    @Test
    fun requestPduCarriesOpnumAndStub() {
        val stub = Srvsvc.netShareEnumAllStub()
        assertEquals(36, stub.size)
        val pdu = Srvsvc.requestPdu(2, 15, stub)
        assertEquals(24 + 36, pdu.size)
        assertContentEquals(hex("05000003 10000000 3c00 0000 02000000 24000000 0000 0f00"), pdu.copyOfRange(0, 24))
        assertContentEquals(stub, pdu.copyOfRange(24, pdu.size))
        assertContentEquals(
            hex("00000000 01000000 01000000 00000200 00000000 00000000 ffffffff 04000200 00000000"),
            stub,
        )
    }

    @Test
    fun singleFragmentResponse() {
        val pipe = FakePipe(bindAck(), responseFragments(responseStub(sample)))
        assertEquals(sample, Srvsvc.netShareEnumAll(pipe))
        assertEquals(2, pipe.requests.size)
        assertEquals(0, pipe.reads)
    }

    @Test
    fun multiFragmentResponseIsReassembled() {
        val many = (0 until 120).map { RawShare("Freigabe-%03d".format(it), 0, "Kommentar Nummer $it mit Umlauten äöü") }
        val fragments = responseFragments(responseStub(many), chunk = 1000)
        assertTrue(fragments.size > 5)
        val pipe = FakePipe(bindAck(), fragments)
        assertEquals(many, Srvsvc.netShareEnumAll(pipe))
        assertEquals(fragments.size - 1, pipe.reads)
    }

    @Test
    fun fragmentsSplitAcrossReadsAreReassembled() {
        val fragments = responseFragments(responseStub(sample), chunk = 40)
        val pipe = FakePipe(bindAck(), fragments, readPiece = 7)
        assertEquals(sample, Srvsvc.netShareEnumAll(pipe))
        assertTrue(pipe.reads > fragments.size)
    }

    @Test
    fun nullCommentsAndEmptyList() {
        val pipe = FakePipe(bindAck(), responseFragments(responseStub(sample, nullComment = true)))
        assertEquals(sample.map { it.copy(comment = "") }, Srvsvc.netShareEnumAll(pipe))
        assertEquals(emptyList(), Srvsvc.netShareEnumAll(FakePipe(bindAck(), responseFragments(responseStub(emptyList())))))
    }

    @Test
    fun bindRejectionsFail() {
        val nak = pdu(13, 3, 1, byteArrayOf(0, 0, 0, 0))
        assertThrows<RpcException> { Srvsvc.netShareEnumAll(FakePipe(nak, emptyList())) }
        assertThrows<RpcException> { Srvsvc.netShareEnumAll(FakePipe(bindAck(result = 2), emptyList())) }
        assertThrows<RpcException> { Srvsvc.netShareEnumAll(FakePipe(bindAck(callId = 9), emptyList())) }
    }

    @Test
    fun faultAndWerrorAccessDenied() {
        val fault = pdu(3, 3, 2, hex("00000000 0000 00 00 05000000 00000000"))
        val e1 = assertThrows<RpcException> { Srvsvc.netShareEnumAll(FakePipe(bindAck(), listOf(fault))) }
        assertTrue(e1.accessDenied)

        val opRange = pdu(3, 3, 2, hex("00000000 0000 00 00 0200011c 00000000"))
        val e2 = assertThrows<RpcException> { Srvsvc.netShareEnumAll(FakePipe(bindAck(), listOf(opRange))) }
        assertFalse(e2.accessDenied)

        val denied = responseFragments(responseStub(emptyList(), werror = 5))
        assertTrue(assertThrows<RpcException> { Srvsvc.netShareEnumAll(FakePipe(bindAck(), denied)) }.accessDenied)

        val other = responseFragments(responseStub(emptyList(), werror = 0x7C))
        assertFalse(assertThrows<RpcException> { Srvsvc.netShareEnumAll(FakePipe(bindAck(), other)) }.accessDenied)

        // ERROR_MORE_DATA still delivers the entries that were returned.
        val more = responseFragments(responseStub(sample, werror = 234))
        assertEquals(sample, Srvsvc.netShareEnumAll(FakePipe(bindAck(), more)))
    }

    @Test
    fun malformedResponsesFailCleanly() {
        val stub = responseStub(sample)
        // Truncated stub.
        val truncated = responseFragments(stub.copyOf(stub.size - 30))
        assertThrows<RpcException> { Srvsvc.netShareEnumAll(FakePipe(bindAck(), truncated)) }
        // Absurd array size.
        val huge = stub.copyOf().also { b -> b[20] = 0xFF.toByte(); b[21] = 0xFF.toByte(); b[22] = 0xFF.toByte(); b[23] = 0x7F }
        assertThrows<RpcException> { Srvsvc.netShareEnumAll(FakePipe(bindAck(), responseFragments(huge))) }
        // Wrong call id.
        assertThrows<RpcException> { Srvsvc.netShareEnumAll(FakePipe(bindAck(), responseFragments(stub, callId = 7))) }
        // Pipe ends in the middle of a fragment.
        val first = responseFragments(stub, chunk = 40)
        assertThrows<RpcException> { Srvsvc.netShareEnumAll(FakePipe(bindAck(), first.dropLast(1))) }
        // Big-endian data representation is not supported.
        val be = responseFragments(stub).single().copyOf().also { it[4] = 0 }
        assertThrows<RpcException> { Srvsvc.netShareEnumAll(FakePipe(bindAck(), listOf(be))) }
    }
}
