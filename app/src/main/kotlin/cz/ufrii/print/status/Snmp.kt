package cz.ufrii.print.status

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

// ---------------------------------------------------------------------------
// Value model
// ---------------------------------------------------------------------------

/**
 * Decoded SNMP varbind value.
 *
 * NOTE: inside this class body the simple name `Int` refers to [SnmpValue.Int];
 * the platform integer type is therefore always spelled `kotlin.Int` here.
 */
sealed class SnmpValue {

    /** INTEGER, Counter32, Gauge32, TimeTicks, Counter64 (unsigned values folded into Long). */
    data class Int(val v: Long) : SnmpValue()

    /** OCTET STRING (raw bytes — may be binary, e.g. hrPrinterDetectedErrorState). */
    data class Str(val bytes: ByteArray) : SnmpValue() {
        /** Bytes as UTF-8 text with trailing NULs removed. */
        fun text(): String = String(bytes, Charsets.UTF_8).trimEnd('\u0000')

        override fun equals(other: Any?): Boolean =
            this === other || (other is Str && bytes.contentEquals(other.bytes))

        override fun hashCode(): kotlin.Int = bytes.contentHashCode()
    }

    /** OBJECT IDENTIFIER in dotted form without a leading dot. */
    data class Oid(val oid: String) : SnmpValue()

    /** noSuchObject / noSuchInstance / endOfMibView (SNMPv2c exceptions). */
    object NoSuch : SnmpValue() {
        override fun toString(): String = "NoSuch"
    }

    /** ASN.1 NULL. */
    object Null : SnmpValue() {
        override fun toString(): String = "Null"
    }

    /** Any other tag (IpAddress, Opaque, ...) kept undecoded. */
    data class Other(val tag: kotlin.Int, val bytes: ByteArray) : SnmpValue() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Other && tag == other.tag && bytes.contentEquals(other.bytes))

        override fun hashCode(): kotlin.Int = 31 * tag + bytes.contentHashCode()
    }
}

/** Decoded SNMP response PDU (GetResponse). */
internal data class SnmpResponse(
    val requestId: Int,
    val errorStatus: Int,
    val errorIndex: Int,
    val varbinds: List<Pair<String, SnmpValue>>
)

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

/**
 * Minimal SNMP client over UDP: GET (many varbinds in one packet) and GETNEXT walk.
 *
 * Wire version is **SNMPv2c** (version field = 1) — that is what was verified against the
 * real Canon MF8030Cn (docs/21); it gives per-varbind noSuchInstance instead of failing the
 * whole request. [VERSION_1] is still supported by the encoder, and the client copes with a
 * v1-style `noSuchName` error (marks the offending OID [SnmpValue.NoSuch] and retries the rest).
 *
 * Rules: **unicast only** (multicast / limited-broadcast targets are refused), only GET/GETNEXT,
 * one request in flight, request-id matching, socket always closed. All OIDs in results are
 * returned in dotted form WITHOUT a leading dot, regardless of how they were requested.
 */
object Snmp {

    internal const val VERSION_1 = 0
    internal const val VERSION_2C = 1

    private const val PORT = 161
    private const val TAG_INTEGER = 0x02
    private const val TAG_OCTET_STRING = 0x04
    private const val TAG_NULL = 0x05
    private const val TAG_OID = 0x06
    private const val TAG_SEQUENCE = 0x30
    internal const val TAG_GET = 0xA0
    internal const val TAG_GETNEXT = 0xA1
    private const val TAG_RESPONSE = 0xA2

    private const val ERR_TOO_BIG = 1
    private const val ERR_NO_SUCH_NAME = 2

    private const val RECV_BUFFER = 4096

    private val nextId = AtomicInteger(Random.nextInt(1, 0x3FFFFFFF))

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * One GET with several varbinds in ONE packet. Returns null on timeout/unreachable.
     * Unicast only. Missing instances appear as [SnmpValue.NoSuch]. If the agent answers with
     * an error (tooBig, genErr, ...) the request is repeated as individual per-OID GETs.
     * The returned map is keyed by the requested OIDs (leading dot stripped).
     */
    suspend fun get(
        host: String,
        oids: List<String>,
        community: String = "public",
        timeoutMs: Int = 2000,
        retries: Int = 1
    ): Map<String, SnmpValue>? = withContext(Dispatchers.IO) {
        if (oids.isEmpty()) return@withContext emptyMap()
        try {
            openSocket(host)?.use { sock ->
                getOnSocket(sock, oids.map { normalizeOid(it) }, community, timeoutMs, retries)
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * GETNEXT walk under [root], at most [limit] entries; empty list if unreachable.
     * On a mid-walk timeout the entries collected so far are returned.
     */
    suspend fun walk(
        host: String,
        root: String,
        limit: Int = 50,
        community: String = "public",
        timeoutMs: Int = 2000
    ): List<Pair<String, SnmpValue>> = withContext(Dispatchers.IO) {
        val out = ArrayList<Pair<String, SnmpValue>>()
        try {
            openSocket(host)?.use { sock ->
                val rootN = normalizeOid(root)
                val seen = HashSet<String>()
                var cur = rootN
                while (out.size < limit) {
                    val id = newRequestId()
                    val resp = exchange(
                        sock, encodeGetNext(id, community, listOf(cur)), id, timeoutMs, retries = 1
                    ) ?: break
                    if (resp.errorStatus != 0) break              // v1 end of MIB = noSuchName
                    val (oid, value) = resp.varbinds.firstOrNull() ?: break
                    if (value === SnmpValue.NoSuch) break         // endOfMibView
                    if (!oid.startsWith("$rootN.")) break         // left the subtree
                    if (!seen.add(oid)) break                     // agent loop guard
                    out.add(oid to value)
                    cur = oid
                }
            }
        } catch (e: IOException) {
            // return what we have
        } catch (e: SecurityException) {
            // return what we have
        }
        out
    }

    // -------------------------------------------------------------------------
    // Request flow
    // -------------------------------------------------------------------------

    /** Opens a UDP socket connected to host:161, or null for refused (non-unicast) targets. */
    private fun openSocket(host: String): DatagramSocket? {
        val addr = InetAddress.getByName(host)
        if (addr.isMulticastAddress || addr.isAnyLocalAddress ||
            addr.hostAddress == "255.255.255.255"
        ) return null
        val sock = DatagramSocket()
        try {
            sock.connect(InetSocketAddress(addr, PORT))   // also filters replies from other hosts
        } catch (e: Exception) {
            sock.close()
            throw e
        }
        return sock
    }

    private fun newRequestId(): Int = nextId.updateAndGet { if (it >= 0x7FFFFFF0) 1 else it + 1 }

    private fun getOnSocket(
        sock: DatagramSocket,
        oids: List<String>,
        community: String,
        timeoutMs: Int,
        retries: Int
    ): Map<String, SnmpValue>? {
        val result = LinkedHashMap<String, SnmpValue>()
        var pending = oids
        var heardFromAgent = false
        var needFallback = false
        var rounds = 0

        while (pending.isNotEmpty() && rounds++ <= oids.size) {
            val id = newRequestId()
            val resp = exchange(sock, encodeGet(id, community, pending), id, timeoutMs, retries)
            if (resp == null) {
                return if (heardFromAgent) result else null
            }
            heardFromAgent = true
            when (resp.errorStatus) {
                0 -> {
                    for ((oid, v) in resp.varbinds) result[oid] = v
                    pending = emptyList()
                }
                ERR_NO_SUCH_NAME -> {
                    // SNMPv1 style: errorIndex (1-based) points at the missing varbind.
                    val idx = resp.errorIndex
                    if (idx in 1..pending.size) {
                        result[pending[idx - 1]] = SnmpValue.NoSuch
                        pending = pending.filterIndexed { i, _ -> i != idx - 1 }
                    } else {
                        needFallback = true
                        break
                    }
                }
                else -> {                                  // tooBig, genErr, ...
                    needFallback = true
                    break
                }
            }
        }
        if (pending.isNotEmpty() && !needFallback) needFallback = true

        if (needFallback) {
            for (oid in pending) {
                val id = newRequestId()
                val resp = exchange(sock, encodeGet(id, community, listOf(oid)), id, timeoutMs, retries)
                    ?: continue
                heardFromAgent = true
                if (resp.errorStatus == 0) {
                    val vb = resp.varbinds.firstOrNull()
                    result[oid] = vb?.second ?: SnmpValue.NoSuch
                } else {
                    result[oid] = SnmpValue.NoSuch
                }
            }
        }
        return if (heardFromAgent) result else null
    }

    /**
     * Sends [request] and waits for the response whose request-id equals [requestId].
     * Foreign / malformed datagrams are ignored. Up to [retries] re-sends; null on timeout.
     */
    private fun exchange(
        sock: DatagramSocket,
        request: ByteArray,
        requestId: Int,
        timeoutMs: Int,
        retries: Int
    ): SnmpResponse? {
        val buf = ByteArray(RECV_BUFFER)
        for (attempt in 0..retries) {
            sock.send(DatagramPacket(request, request.size))
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (true) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0) break
                sock.soTimeout = remainingMs.toInt().coerceAtLeast(1)
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val resp = decodeResponse(buf.copyOf(pkt.length)) ?: continue
                if (resp.requestId == requestId) return resp
            }
        }
        return null
    }

    // -------------------------------------------------------------------------
    // BER encoder
    // -------------------------------------------------------------------------

    /** GET request (PDU 0xA0) with all [oids] as varbinds (value NULL). */
    internal fun encodeGet(
        requestId: Int,
        community: String,
        oids: List<String>,
        version: Int = VERSION_2C
    ): ByteArray = encodeRequest(TAG_GET, requestId, community, oids, version)

    /** GETNEXT request (PDU 0xA1). */
    internal fun encodeGetNext(
        requestId: Int,
        community: String,
        oids: List<String>,
        version: Int = VERSION_2C
    ): ByteArray = encodeRequest(TAG_GETNEXT, requestId, community, oids, version)

    private fun encodeRequest(
        pduTag: Int,
        requestId: Int,
        community: String,
        oids: List<String>,
        version: Int
    ): ByteArray {
        val varbinds = oids.fold(ByteArray(0)) { acc, oid ->
            acc + tlv(TAG_SEQUENCE, encodeOid(oid) + tlv(TAG_NULL, ByteArray(0)))
        }
        val pdu = tlv(
            pduTag,
            encodeInt(requestId.toLong()) + encodeInt(0) + encodeInt(0) + tlv(TAG_SEQUENCE, varbinds)
        )
        return tlv(
            TAG_SEQUENCE,
            encodeInt(version.toLong()) +
                tlv(TAG_OCTET_STRING, community.toByteArray(Charsets.ISO_8859_1)) + pdu
        )
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + encodeLength(value.size) + value

    internal fun encodeLength(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        val bytes = ArrayList<Byte>()
        var x = n
        while (x > 0) {
            bytes.add((x and 0xFF).toByte())
            x = x ushr 8
        }
        bytes.reverse()
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    /** INTEGER TLV, minimal two's-complement encoding. */
    internal fun encodeInt(n: Long): ByteArray {
        var len = 1
        while (len < 8 && (n shr (len * 8 - 1)) != 0L && (n shr (len * 8 - 1)) != -1L) len++
        val v = ByteArray(len) { i -> (n shr ((len - 1 - i) * 8)).toByte() }
        return tlv(TAG_INTEGER, v)
    }

    /** OBJECT IDENTIFIER TLV from dotted notation (leading dot allowed). */
    internal fun encodeOid(oid: String): ByteArray {
        val parts = normalizeOid(oid).split('.').map {
            it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw IllegalArgumentException("Bad OID: $oid")
        }
        require(parts.size >= 2 && parts[0] <= 2 && (parts[0] == 2L || parts[1] < 40)) { "Bad OID: $oid" }
        val out = java.io.ByteArrayOutputStream()
        writeSubId(out, parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) writeSubId(out, parts[i])
        return tlv(TAG_OID, out.toByteArray())
    }

    private fun writeSubId(out: java.io.ByteArrayOutputStream, value: Long) {
        val tmp = ArrayList<Int>()
        var x = value
        tmp.add((x and 0x7F).toInt())
        x = x ushr 7
        while (x > 0) {
            tmp.add(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        for (i in tmp.indices.reversed()) out.write(tmp[i])
    }

    internal fun normalizeOid(oid: String): String = oid.trim().trimStart('.')

    // -------------------------------------------------------------------------
    // BER decoder
    // -------------------------------------------------------------------------

    /** One parsed TLV: [tag] and its value bytes at buf[start, end) (the TLV itself ends at [end]). */
    private class Tlv(val tag: Int, val start: Int, val end: Int)

    private fun readTlv(buf: ByteArray, pos: Int, limit: Int): Tlv {
        require(pos + 2 <= limit) { "truncated header" }
        val tag = buf[pos].toInt() and 0xFF
        require(tag and 0x1F != 0x1F) { "multi-byte tags unsupported" }
        var p = pos + 1
        var len = buf[p++].toInt() and 0xFF
        if (len and 0x80 != 0) {
            val n = len and 0x7F
            require(n in 1..4 && p + n <= limit) { "bad length" }
            len = 0
            repeat(n) { len = (len shl 8) or (buf[p++].toInt() and 0xFF) }
            require(len >= 0) { "bad length" }
        }
        require(len <= limit - p) { "length exceeds buffer" }
        return Tlv(tag, p, p + len)
    }

    private fun signedValue(buf: ByteArray, start: Int, end: Int): Long {
        require(end - start in 1..9) { "bad integer" }
        var v = if (buf[start] < 0) -1L else 0L
        for (i in start until end) v = (v shl 8) or (buf[i].toLong() and 0xFF)
        return v
    }

    /**
     * Decodes an SNMP message. Returns null for anything that is not a well-formed
     * GetResponse (wrong PDU type, truncated, garbage, ...). Accepts v1 and v2c.
     */
    internal fun decodeResponse(data: ByteArray): SnmpResponse? = try {
        parseResponse(data)
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private fun parseResponse(b: ByteArray): SnmpResponse? {
        val msg = readTlv(b, 0, b.size)
        require(msg.tag == TAG_SEQUENCE)
        val ver = readTlv(b, msg.start, msg.end)
        require(ver.tag == TAG_INTEGER)
        val comm = readTlv(b, ver.end, msg.end)
        require(comm.tag == TAG_OCTET_STRING)
        val pdu = readTlv(b, comm.end, msg.end)
        if (pdu.tag != TAG_RESPONSE) return null
        val rid = readTlv(b, pdu.start, pdu.end)
        val es = readTlv(b, rid.end, pdu.end)
        val ei = readTlv(b, es.end, pdu.end)
        val vbl = readTlv(b, ei.end, pdu.end)
        require(rid.tag == TAG_INTEGER && es.tag == TAG_INTEGER && ei.tag == TAG_INTEGER &&
            vbl.tag == TAG_SEQUENCE)

        val varbinds = ArrayList<Pair<String, SnmpValue>>()
        var p = vbl.start
        while (p < vbl.end) {
            val vb = readTlv(b, p, vbl.end)
            require(vb.tag == TAG_SEQUENCE)
            val o = readTlv(b, vb.start, vb.end)
            require(o.tag == TAG_OID)
            val v = readTlv(b, o.end, vb.end)
            varbinds.add(decodeOid(b, o.start, o.end) to decodeValue(b, v))
            p = vb.end
        }
        return SnmpResponse(
            requestId = signedValue(b, rid.start, rid.end).toInt(),
            errorStatus = signedValue(b, es.start, es.end).toInt(),
            errorIndex = signedValue(b, ei.start, ei.end).toInt(),
            varbinds = varbinds
        )
    }

    private fun decodeOid(b: ByteArray, start: Int, end: Int): String {
        require(end > start) { "empty OID" }
        val first = b[start].toInt() and 0xFF
        val sb = StringBuilder()
        when {
            first < 40 -> sb.append("0.").append(first)
            first < 80 -> sb.append("1.").append(first - 40)
            else -> sb.append("2.").append(first - 80)
        }
        var x = 0L
        for (i in start + 1 until end) {
            val c = b[i].toInt() and 0xFF
            x = (x shl 7) or (c and 0x7F).toLong()
            if (c and 0x80 == 0) {
                sb.append('.').append(x)
                x = 0
            }
        }
        return sb.toString()
    }

    private fun decodeValue(b: ByteArray, t: Tlv): SnmpValue = when (t.tag) {
        TAG_INTEGER -> SnmpValue.Int(signedValue(b, t.start, t.end))
        TAG_OCTET_STRING -> SnmpValue.Str(b.copyOfRange(t.start, t.end))
        TAG_NULL -> SnmpValue.Null
        TAG_OID -> SnmpValue.Oid(decodeOid(b, t.start, t.end))
        0x41, 0x42, 0x43 -> SnmpValue.Int(BigInteger(1, b.copyOfRange(t.start, t.end)).toLong())
        0x46 -> SnmpValue.Int(BigInteger(1, b.copyOfRange(t.start, t.end)).toLong())
        0x80, 0x81, 0x82 -> SnmpValue.NoSuch
        else -> SnmpValue.Other(t.tag, b.copyOfRange(t.start, t.end))
    }
}
