package de.lulurobb.keypassmobile.smb.rpc

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Message transport for connection-oriented DCE/RPC over an SMB named pipe.
 * Each PDU fragment is one pipe message.
 */
internal interface RpcTransport {
    /** Writes [pdu] and returns the first response message (FSCTL_PIPE_TRANSCEIVE). */
    fun transact(pdu: ByteArray): ByteArray

    /** Reads the next message from the pipe, at most [maxLength] bytes. */
    fun read(maxLength: Int): ByteArray
}

/** A share as returned by NetShareEnumAll level 1, before any filtering. */
internal data class RawShare(val name: String, val type: Long, val comment: String)

/**
 * Minimal client for the SRVSVC interface (MS-SRVS): DCE/RPC bind plus one
 * NetrShareEnum call (opnum 15, called NetShareEnumAll in Samba) with info level 1.
 * No authentication on the RPC level; the SMB session already authenticates and
 * signs or encrypts the pipe traffic.
 *
 * References: C706 chapter 12 (connection-oriented PDUs) and chapter 14 (NDR),
 * MS-RPCE 2.2.2, MS-SRVS 3.1.4.8.
 */
internal object Srvsvc {
    /** Pipe name on IPC$. */
    const val PIPE_NAME = "srvsvc"

    private val SRVSVC_UUID: UUID = UUID.fromString("4b324fc8-1670-01d3-1278-5a47bf6ee188")
    private const val SRVSVC_VERSION_MAJOR = 3
    private const val SRVSVC_VERSION_MINOR = 0
    private val NDR20_UUID: UUID = UUID.fromString("8a885d04-1ceb-11c9-9fe8-08002b104860")
    private const val NDR20_VERSION = 2L

    private const val OPNUM_NET_SHARE_ENUM_ALL = 15

    private const val PTYPE_REQUEST = 0
    private const val PTYPE_RESPONSE = 2
    private const val PTYPE_FAULT = 3
    private const val PTYPE_BIND = 11
    private const val PTYPE_BIND_ACK = 12
    private const val PTYPE_BIND_NAK = 13

    private const val PFC_FIRST_FRAG = 0x01
    private const val PFC_LAST_FRAG = 0x02

    private const val HEADER_LENGTH = 16
    private const val RESPONSE_HEADER_LENGTH = 24

    /** Fragment size used by Windows clients; every server accepts it. */
    const val MAX_FRAG = 4280

    /** Upper bound for the reassembled response, protects against a hostile server. */
    private const val MAX_STUB_BYTES = 4 * 1024 * 1024

    /** Upper bound for the number of shares in one response. */
    private const val MAX_SHARES = 10_000

    private const val NCA_ACCESS_DENIED = 0x00000005L
    private const val WERR_ACCESS_DENIED = 5L
    private const val WERR_MORE_DATA = 234L

    private const val BIND_CALL_ID = 1L
    private const val ENUM_CALL_ID = 2L

    /** Binds to SRVSVC and returns all shares the server reports (level 1). */
    fun netShareEnumAll(transport: RpcTransport): List<RawShare> {
        val pdus = PduStream(transport)
        pdus.feed(transport.transact(bindPdu(BIND_CALL_ID)))
        val maxXmitFrag = parseBindAck(pdus.next(), BIND_CALL_ID)
        pdus.readSize = maxOf(maxXmitFrag, MAX_FRAG) + HEADER_LENGTH

        pdus.feed(transport.transact(requestPdu(ENUM_CALL_ID, OPNUM_NET_SHARE_ENUM_ALL, netShareEnumAllStub())))
        val stub = ByteArrayOutputStream()
        var first = true
        while (true) {
            val fragment = parseResponse(pdus.next(), ENUM_CALL_ID, first)
            first = false
            stub.write(fragment.stub)
            if (stub.size() > MAX_STUB_BYTES) throw RpcException("RPC response too large")
            if (fragment.last) break
        }
        return parseNetShareEnumAllResponse(stub.toByteArray())
    }

    /** BIND PDU for SRVSVC v3.0 with transfer syntax NDR 2.0. */
    internal fun bindPdu(callId: Long): ByteArray {
        val body = NdrWriter().apply {
            u16(MAX_FRAG) // max_xmit_frag
            u16(MAX_FRAG) // max_recv_frag
            u32(0) // assoc_group_id: new association
            u8(1) // n_context_elem
            u8(0)
            u16(0)
            u16(0) // p_cont_id
            u8(1) // n_transfer_syn
            u8(0)
            uuid(SRVSVC_UUID)
            u16(SRVSVC_VERSION_MAJOR)
            u16(SRVSVC_VERSION_MINOR)
            uuid(NDR20_UUID)
            u32(NDR20_VERSION)
        }.toByteArray()
        return pdu(PTYPE_BIND, callId, body)
    }

    /** REQUEST PDU in a single fragment (our requests are tiny). */
    internal fun requestPdu(callId: Long, opnum: Int, stub: ByteArray): ByteArray {
        val body = NdrWriter().apply {
            u32(stub.size.toLong()) // alloc_hint
            u16(0) // p_cont_id
            u16(opnum)
            bytes(stub)
        }.toByteArray()
        return pdu(PTYPE_REQUEST, callId, body)
    }

    /**
     * Request stub of NetrShareEnum: ServerName = NULL, InfoStruct level 1 with an
     * empty container, PreferedMaximumLength = MAX_PREFERRED_LENGTH, ResumeHandle = 0.
     */
    internal fun netShareEnumAllStub(): ByteArray = NdrWriter().apply {
        u32(0) // ServerName: unique pointer, NULL
        u32(1) // InfoStruct.Level
        u32(1) // union discriminant
        u32(0x00020000) // referent id of SHARE_INFO_1_CONTAINER
        u32(0) // EntriesRead
        u32(0) // Buffer: NULL
        u32(0xFFFFFFFFL) // PreferedMaximumLength
        u32(0x00020004) // ResumeHandle: unique pointer
        u32(0) // *ResumeHandle
    }.toByteArray()

    private fun pdu(ptype: Int, callId: Long, body: ByteArray): ByteArray {
        val length = HEADER_LENGTH + body.size
        return NdrWriter().apply {
            u8(5) // rpc_vers
            u8(0) // rpc_vers_minor
            u8(ptype)
            u8(PFC_FIRST_FRAG or PFC_LAST_FRAG)
            bytes(byteArrayOf(0x10, 0, 0, 0)) // data representation: little-endian, ASCII, IEEE
            u16(length)
            u16(0) // auth_length
            u32(callId)
            bytes(body)
        }.toByteArray()
    }

    private class Header(val ptype: Int, val flags: Int, val fragLength: Int, val authLength: Int, val callId: Long)

    private fun header(pdu: ByteArray): Header {
        val r = NdrReader(pdu)
        val version = r.u8()
        val minor = r.u8()
        val ptype = r.u8()
        val flags = r.u8()
        val drep = r.u8()
        r.skip(3)
        val fragLength = r.u16()
        val authLength = r.u16()
        val callId = r.u32()
        if (version != 5 || minor > 1) throw RpcException("Unsupported RPC version $version.$minor")
        if ((drep and 0xF0) != 0x10) throw RpcException("Unsupported RPC data representation")
        if (fragLength != pdu.size) throw RpcException("RPC fragment length mismatch")
        return Header(ptype, flags, fragLength, authLength, callId)
    }

    /** Throws for BIND_NAK, FAULT and any non-acceptance; returns the server's max_xmit_frag. */
    internal fun parseBindAck(pdu: ByteArray, callId: Long): Int {
        val h = header(pdu)
        when (h.ptype) {
            PTYPE_BIND_ACK -> Unit
            PTYPE_BIND_NAK -> throw RpcException("RPC bind rejected")
            PTYPE_FAULT -> throw fault(pdu)
            else -> throw RpcException("Unexpected RPC packet type ${h.ptype}")
        }
        if (h.callId != callId) throw RpcException("RPC call id mismatch")
        val r = NdrReader(pdu, 0, h.fragLength - h.authLength)
        r.skip(HEADER_LENGTH)
        val maxXmitFrag = r.u16()
        r.u16() // max_recv_frag
        r.u32() // assoc_group_id
        val secAddrLength = r.u16()
        r.skip(secAddrLength)
        r.align(4)
        val results = r.u8()
        r.skip(3)
        if (results < 1) throw RpcException("RPC bind without result")
        val result = r.u16()
        if (result != 0) throw RpcException("RPC bind not accepted (result $result, reason ${r.u16()})")
        return maxXmitFrag
    }

    internal class ResponseFragment(val stub: ByteArray, val last: Boolean)

    /** Extracts the stub data of one RESPONSE fragment; throws for FAULT. */
    internal fun parseResponse(pdu: ByteArray, callId: Long, first: Boolean): ResponseFragment {
        val h = header(pdu)
        when (h.ptype) {
            PTYPE_RESPONSE -> Unit
            PTYPE_FAULT -> throw fault(pdu)
            else -> throw RpcException("Unexpected RPC packet type ${h.ptype}")
        }
        if (h.callId != callId) throw RpcException("RPC call id mismatch")
        if (first && (h.flags and PFC_FIRST_FRAG) == 0) throw RpcException("RPC first fragment missing")
        // With authentication a security trailer (8 bytes) and the token follow the stub.
        val stubEnd = h.fragLength - (if (h.authLength > 0) h.authLength + 8 else 0)
        if (stubEnd < RESPONSE_HEADER_LENGTH) throw RpcException("Truncated RPC response")
        return ResponseFragment(pdu.copyOfRange(RESPONSE_HEADER_LENGTH, stubEnd), (h.flags and PFC_LAST_FRAG) != 0)
    }

    private fun fault(pdu: ByteArray): RpcException {
        val r = NdrReader(pdu)
        r.skip(RESPONSE_HEADER_LENGTH)
        val status = if (r.remaining >= 4) r.u32() else -1L
        return RpcException(
            "RPC fault 0x%08X".format(status),
            accessDenied = status == NCA_ACCESS_DENIED,
        )
    }

    /** Decodes the NetrShareEnum response stub (level 1). */
    internal fun parseNetShareEnumAllResponse(stub: ByteArray): List<RawShare> {
        val r = NdrReader(stub)
        val level = r.u32()
        val discriminant = r.u32()
        if (level != 1L || discriminant != 1L) throw RpcException("Unexpected share info level $level")
        val containerPtr = r.u32()
        val shares = mutableListOf<RawShare>()
        if (containerPtr != 0L) {
            r.u32() // EntriesRead; the array conformance below is authoritative
            val bufferPtr = r.u32()
            if (bufferPtr != 0L) {
                val count = r.u32()
                if (count > MAX_SHARES) throw RpcException("Too many shares")
                class Entry(val namePtr: Long, val type: Long, val commentPtr: Long)
                val entries = List(count.toInt()) { Entry(r.u32(), r.u32(), r.u32()) }
                // Deferred pointees follow the array in order of appearance.
                for (e in entries) {
                    val name = if (e.namePtr != 0L) r.wideString() else ""
                    val comment = if (e.commentPtr != 0L) r.wideString() else ""
                    shares += RawShare(name, e.type, comment)
                }
            }
        }
        r.u32() // TotalEntries
        if (r.u32() != 0L) r.u32() // ResumeHandle
        val werror = r.u32()
        when (werror) {
            0L, WERR_MORE_DATA -> Unit
            WERR_ACCESS_DENIED -> throw RpcException("NetShareEnumAll: access denied", accessDenied = true)
            else -> throw RpcException("NetShareEnumAll failed with WERROR 0x%08X".format(werror))
        }
        return shares
    }

    /** Splits the pipe byte stream into PDUs; reads further messages on demand. */
    private class PduStream(private val transport: RpcTransport) {
        private var data = ByteArray(0)
        var readSize: Int = MAX_FRAG + HEADER_LENGTH

        fun feed(bytes: ByteArray) {
            data += bytes
        }

        fun next(): ByteArray {
            while (true) {
                if (data.size >= HEADER_LENGTH) {
                    val fragLength = (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8)
                    if (fragLength < HEADER_LENGTH) throw RpcException("Malformed RPC fragment")
                    if (data.size >= fragLength) {
                        val pdu = data.copyOfRange(0, fragLength)
                        data = data.copyOfRange(fragLength, data.size)
                        return pdu
                    }
                }
                val more = transport.read(readSize)
                if (more.isEmpty()) throw RpcException("RPC pipe returned no data")
                feed(more)
            }
        }
    }
}
