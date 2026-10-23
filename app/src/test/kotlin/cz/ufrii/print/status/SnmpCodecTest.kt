package cz.ufrii.print.status

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BER / SNMP codec tests — pure JVM, no network. */
class SnmpCodecTest {

    // -------------------------------------------------------------------------
    // Helpers (deliberately independent of the production encoder)
    // -------------------------------------------------------------------------

    private fun hex(s: String): ByteArray =
        s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun tlv(tag: Int, v: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + len(v.size) + v

    private val sysDescrOid = hex("2B 06 01 02 01 01 01 00")   // 1.3.6.1.2.1.1.1.0

    private fun varbind(oidContent: ByteArray, value: ByteArray): ByteArray =
        tlv(0x30, tlv(0x06, oidContent) + value)

    private fun response(
        requestId: Int = 1,
        errorStatus: Int = 0,
        errorIndex: Int = 0,
        pduTag: Int = 0xA2,
        vararg varbinds: ByteArray
    ): ByteArray {
        val vbl = tlv(0x30, varbinds.fold(ByteArray(0)) { a, b -> a + b })
        val pdu = tlv(
            pduTag,
            tlv(2, byteArrayOf(requestId.toByte())) +
                tlv(2, byteArrayOf(errorStatus.toByte())) +
                tlv(2, byteArrayOf(errorIndex.toByte())) + vbl
        )
        return tlv(0x30, tlv(2, byteArrayOf(1)) + tlv(4, "public".toByteArray()) + pdu)
    }

    private fun single(value: ByteArray): SnmpValue {
        val r = Snmp.decodeResponse(response(1, 0, 0, 0xA2, varbind(sysDescrOid, value)))
        assertNotNull(r)
        assertEquals(1, r!!.varbinds.size)
        assertEquals("1.3.6.1.2.1.1.1.0", r.varbinds[0].first)
        return r.varbinds[0].second
    }

    // -------------------------------------------------------------------------
    // Encoder
    // -------------------------------------------------------------------------

    @Test
    fun encodeGet_singleOid_v2c_matchesKnownBytes() {
        val expected = hex(
            "30 26 02 01 01 04 06 70 75 62 6C 69 63 " +
                "A0 19 02 01 01 02 01 00 02 01 00 30 0E 30 0C 06 08 2B 06 01 02 01 01 01 00 05 00"
        )
        assertArrayEquals(expected, Snmp.encodeGet(1, "public", listOf("1.3.6.1.2.1.1.1.0")))
    }

    @Test
    fun encodeGet_leadingDotIsIgnored() {
        assertArrayEquals(
            Snmp.encodeGet(7, "public", listOf("1.3.6.1.2.1.1.1.0")),
            Snmp.encodeGet(7, "public", listOf(".1.3.6.1.2.1.1.1.0"))
        )
    }

    @Test
    fun encodeGet_v1_hasVersionZero() {
        val b = Snmp.encodeGet(1, "public", listOf("1.3.6.1.2.1.1.1.0"), Snmp.VERSION_1)
        assertEquals(0x30, b[0].toInt())
        assertArrayEquals(hex("02 01 00"), b.copyOfRange(2, 5))
    }

    @Test
    fun encodeGetNext_usesPduTagA1() {
        val b = Snmp.encodeGetNext(1, "public", listOf("1.3.6.1.2.1.43.18.1.1"))
        assertEquals(0xA1, b[13].toInt() and 0xFF)
    }

    @Test
    fun encodeGet_multipleOids_oneVarbindEach() {
        val oids = listOf("1.3.6.1.2.1.1.1.0", "1.3.6.1.2.1.25.3.2.1.3.1", "1.3.6.1.2.1.43.10.2.1.4.1.1")
        val b = Snmp.encodeGet(5, "public", oids)
        // outer SEQUENCE length must match the datagram size
        assertEquals(b.size - 2, b[1].toInt() and 0xFF)
        // three varbind SEQUENCEs: count "30 xx 06" patterns after the PDU header
        var count = 0
        for (i in 0 until b.size - 2) {
            if (b[i].toInt() == 0x30 && b[i + 2].toInt() == 0x06) count++
        }
        assertEquals(3, count)
    }

    @Test
    fun encodeOid_multiByteSubidentifier() {
        // 1.3.6.1.4.1.1602 (Canon): 1602 = 0x8C 0x42 in base-128
        assertArrayEquals(
            hex("06 07 2B 06 01 04 01 8C 42"),
            Snmp.encodeOid("1.3.6.1.4.1.1602")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodeOid_rejectsGarbage() {
        Snmp.encodeOid("1.3.x")
    }

    @Test
    fun encodeInt_isMinimalTwosComplement() {
        assertArrayEquals(hex("02 01 00"), Snmp.encodeInt(0))
        assertArrayEquals(hex("02 01 7F"), Snmp.encodeInt(127))
        assertArrayEquals(hex("02 02 00 80"), Snmp.encodeInt(128))
        assertArrayEquals(hex("02 02 03 E8"), Snmp.encodeInt(1000))
        assertArrayEquals(hex("02 01 FF"), Snmp.encodeInt(-1))
        assertArrayEquals(hex("02 01 80"), Snmp.encodeInt(-128))
        assertArrayEquals(hex("02 02 FF 7F"), Snmp.encodeInt(-129))
    }

    @Test
    fun encodeLength_shortAndLongForm() {
        assertArrayEquals(hex("05"), Snmp.encodeLength(5))
        assertArrayEquals(hex("7F"), Snmp.encodeLength(127))
        assertArrayEquals(hex("81 80"), Snmp.encodeLength(128))
        assertArrayEquals(hex("81 C8"), Snmp.encodeLength(200))
        assertArrayEquals(hex("82 01 2C"), Snmp.encodeLength(300))
    }

    // -------------------------------------------------------------------------
    // Decoder
    // -------------------------------------------------------------------------

    @Test
    fun decodeResponse_handBuiltBytes() {
        val bytes = hex(
            "30 2B 02 01 01 04 06 70 75 62 6C 69 63 " +
                "A2 1E 02 01 01 02 01 00 02 01 00 30 13 30 11 06 08 2B 06 01 02 01 01 01 00 " +
                "04 05 43 61 6E 6F 6E"
        )
        val r = Snmp.decodeResponse(bytes)
        assertNotNull(r)
        assertEquals(1, r!!.requestId)
        assertEquals(0, r.errorStatus)
        assertEquals(0, r.errorIndex)
        assertEquals(1, r.varbinds.size)
        assertEquals("1.3.6.1.2.1.1.1.0", r.varbinds[0].first)
        assertEquals(SnmpValue.Str("Canon".toByteArray()), r.varbinds[0].second)
        assertEquals("Canon", (r.varbinds[0].second as SnmpValue.Str).text())
    }

    @Test
    fun decodeValue_integers() {
        assertEquals(SnmpValue.Int(-3), single(hex("02 01 FD")))
        assertEquals(SnmpValue.Int(0), single(hex("02 01 00")))
        assertEquals(SnmpValue.Int(128), single(hex("02 02 00 80")))
        assertEquals(SnmpValue.Int(-2), single(hex("02 01 FE")))
    }

    @Test
    fun decodeValue_unsignedTypes() {
        assertEquals(SnmpValue.Int(4294967295L), single(hex("42 04 FF FF FF FF")))   // Gauge32
        assertEquals(SnmpValue.Int(53739), single(hex("41 02 D1 EB")))               // Counter32
        assertEquals(SnmpValue.Int(100), single(hex("43 01 64")))                    // TimeTicks
        assertEquals(SnmpValue.Int(5), single(hex("46 01 05")))                      // Counter64
    }

    @Test
    fun decodeValue_stringTrailingNulStripped() {
        val v = single(hex("04 04 41 42 00 00")) as SnmpValue.Str
        assertEquals("AB", v.text())
    }

    @Test
    fun decodeValue_longFormLengthString() {
        val payload = ByteArray(200) { 'x'.code.toByte() }
        val v = single(tlv(4, payload)) as SnmpValue.Str
        assertEquals(200, v.bytes.size)
    }

    @Test
    fun decodeValue_exceptionsAndMisc() {
        assertEquals(SnmpValue.NoSuch, single(hex("81 00")))
        assertEquals(SnmpValue.NoSuch, single(hex("80 00")))
        assertEquals(SnmpValue.NoSuch, single(hex("82 00")))
        assertEquals(SnmpValue.Null, single(hex("05 00")))
        assertEquals(SnmpValue.Oid("1.3.6.1.4.1.1602"), single(hex("06 07 2B 06 01 04 01 8C 42")))
        assertEquals(SnmpValue.Other(0x40, hex("C0 A8 00 3E")), single(hex("40 04 C0 A8 00 3E")))
    }

    @Test
    fun decodeResponse_errorStatusAndIndex() {
        val r = Snmp.decodeResponse(
            response(9, 2, 2, 0xA2, varbind(sysDescrOid, hex("05 00")), varbind(sysDescrOid, hex("05 00")))
        )
        assertNotNull(r)
        assertEquals(9, r!!.requestId)
        assertEquals(2, r.errorStatus)
        assertEquals(2, r.errorIndex)
        assertEquals(2, r.varbinds.size)
    }

    @Test
    fun decodeResponse_multipleVarbindsKeepOrder() {
        val oid2 = hex("2B 06 01 02 01 19 03 02 01 05 01")        // 1.3.6.1.2.1.25.3.2.1.5.1
        val r = Snmp.decodeResponse(
            response(
                3, 0, 0, 0xA2,
                varbind(sysDescrOid, hex("04 02 68 69")),
                varbind(oid2, hex("02 01 02"))
            )
        )!!
        assertEquals(listOf("1.3.6.1.2.1.1.1.0", "1.3.6.1.2.1.25.3.2.1.5.1"), r.varbinds.map { it.first })
        assertEquals(SnmpValue.Int(2), r.varbinds[1].second)
    }

    @Test
    fun decodeResponse_rejectsNonResponsePdu() {
        // a GET request (0xA0) is not a response
        assertNull(Snmp.decodeResponse(response(1, 0, 0, 0xA0, varbind(sysDescrOid, hex("05 00")))))
    }

    @Test
    fun decodeResponse_rejectsGarbageAndTruncation() {
        assertNull(Snmp.decodeResponse(ByteArray(0)))
        assertNull(Snmp.decodeResponse(hex("FF FF FF")))
        assertNull(Snmp.decodeResponse(hex("30 82 FF FF 00")))
        val good = response(1, 0, 0, 0xA2, varbind(sysDescrOid, hex("04 05 43 61 6E 6F 6E")))
        for (cut in 1 until good.size) {
            // every proper prefix must be rejected without throwing
            assertNull("prefix $cut", Snmp.decodeResponse(good.copyOf(cut)))
        }
        assertTrue(Snmp.decodeResponse(good) != null)
    }
}
