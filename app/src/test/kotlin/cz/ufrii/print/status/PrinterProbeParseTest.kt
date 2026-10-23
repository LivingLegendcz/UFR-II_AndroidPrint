package cz.ufrii.print.status

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parse-helper tests using values measured on a Canon MF8030Cn (docs/21). */
class PrinterProbeParseTest {

    private fun str(s: String) = SnmpValue.Str(s.toByteArray())
    private fun int(n: Long) = SnmpValue.Int(n)

    // -------------------------------------------------------------------------
    // Toners
    // -------------------------------------------------------------------------

    /** Measured order: index 1..4 = K, Y, M, C (NOT CMYK). */
    private fun tonerMap(levels: List<Long> = listOf(100, 100, 100, 100), max: Long = 100): Map<String, SnmpValue> {
        val names = listOf("Black", "Yellow", "Magenta", "Cyan")
        val m = HashMap<String, SnmpValue>()
        for (i in 1..4) {
            m["1.3.6.1.2.1.43.11.1.1.6.1.$i"] = str("Canon CRG316 ${names[i - 1]} Toner")
            m["1.3.6.1.2.1.43.11.1.1.8.1.$i"] = int(max)
            m["1.3.6.1.2.1.43.11.1.1.9.1.$i"] = int(levels[i - 1])
        }
        // indexes 5, 6 do not exist
        m["1.3.6.1.2.1.43.11.1.1.6.1.5"] = SnmpValue.NoSuch
        return m
    }

    @Test
    fun parseToners_colorComesFromDescriptionNotIndex() {
        val t = PrinterProbe.parseToners(tonerMap())
        assertEquals(4, t.size)
        assertEquals(
            listOf(TonerColor.BLACK, TonerColor.YELLOW, TonerColor.MAGENTA, TonerColor.CYAN),
            t.map { it.color }
        )
        assertEquals(listOf(1, 2, 3, 4), t.map { it.index })
        assertTrue(t.all { it.levelPercent == 100 })
        assertEquals("Canon CRG316 Black Toner", t[0].description)
    }

    @Test
    fun parseToners_searchText() {
        val t = PrinterProbe.parseToners(tonerMap())
        assertEquals("Canon Cartridge 716 Black (CRG-316)", t[0].searchText)
        assertEquals("Canon Cartridge 716 Yellow (CRG-316)", t[1].searchText)
        assertEquals("Canon Cartridge 716 Magenta (CRG-316)", t[2].searchText)
        assertEquals("Canon Cartridge 716 Cyan (CRG-316)", t[3].searchText)
    }

    @Test
    fun parseToners_negativeLevelsAreUnknown() {
        val t = PrinterProbe.parseToners(tonerMap(listOf(-2, -3, 40, 0)))
        assertNull(t[0].levelPercent)   // -2 unknown
        assertNull(t[1].levelPercent)   // -3 some remaining
        assertEquals(40, t[2].levelPercent)
        assertEquals(0, t[3].levelPercent)
    }

    @Test
    fun parseToners_percentFromLevelAndMax() {
        val t = PrinterProbe.parseToners(tonerMap(listOf(5, 10, 0, 7), max = 10))
        assertEquals(listOf(50, 100, 0, 70), t.map { it.levelPercent })
    }

    @Test
    fun parseToners_leadingDotKeysAccepted() {
        val dotted = tonerMap().mapKeys { "." + it.key }
        assertEquals(4, PrinterProbe.parseToners(dotted).size)
    }

    @Test
    fun parseToners_empty() {
        assertTrue(PrinterProbe.parseToners(emptyMap()).isEmpty())
    }

    @Test
    fun tonerColorOf_caseInsensitive() {
        assertEquals(TonerColor.BLACK, PrinterProbe.tonerColorOf("Canon CRG316 BLACK Toner"))
        assertEquals(TonerColor.CYAN, PrinterProbe.tonerColorOf("cyan toner"))
        assertEquals(TonerColor.MAGENTA, PrinterProbe.tonerColorOf("Magenta"))
        assertEquals(TonerColor.YELLOW, PrinterProbe.tonerColorOf("Yellow Cartridge"))
        assertEquals(TonerColor.OTHER, PrinterProbe.tonerColorOf("Waste toner box"))
    }

    @Test
    fun searchTextOf_regionalNamingOnlyForCrg3xx() {
        assertEquals("Canon Cartridge 716 Yellow (CRG-316)", PrinterProbe.searchTextOf("Canon CRG316 Yellow Toner"))
        assertEquals("Canon Cartridge 731 Cyan (CRG-331)", PrinterProbe.searchTextOf("Canon CRG-331 Cyan Toner"))
        assertEquals("Canon Cartridge 731 Cyan (CRG-331)", PrinterProbe.searchTextOf("Canon CRG 331 Cyan Toner"))
        assertEquals("HP 305A Cyan", PrinterProbe.searchTextOf("  HP 305A Cyan "))
        assertEquals("Canon CRG-418 Black", PrinterProbe.searchTextOf("Canon CRG-418 Black"))
    }

    @Test
    fun isColor_rules() {
        val color = PrinterProbe.parseToners(tonerMap())
        assertEquals(true, PrinterProbe.isColor(color))
        assertEquals(false, PrinterProbe.isColor(color.filter { it.color == TonerColor.BLACK }))
        assertNull(PrinterProbe.isColor(emptyList()))
    }

    // -------------------------------------------------------------------------
    // Inputs
    // -------------------------------------------------------------------------

    private fun inputMap(
        bypassLevel: Long, bypassStatus: Long, drawerLevel: Long, drawerStatus: Long
    ): Map<String, SnmpValue> = mapOf(
        "1.3.6.1.2.1.43.8.2.1.2.1.1" to int(5),
        "1.3.6.1.2.1.43.8.2.1.13.1.1" to str("Stack Bypass"),
        "1.3.6.1.2.1.43.8.2.1.9.1.1" to int(1),
        "1.3.6.1.2.1.43.8.2.1.10.1.1" to int(bypassLevel),
        "1.3.6.1.2.1.43.8.2.1.11.1.1" to int(bypassStatus),
        "1.3.6.1.2.1.43.8.2.1.2.1.2" to int(4),
        "1.3.6.1.2.1.43.8.2.1.13.1.2" to str("Drawer1"),
        "1.3.6.1.2.1.43.8.2.1.9.1.2" to int(150),
        "1.3.6.1.2.1.43.8.2.1.10.1.2" to int(drawerLevel),
        "1.3.6.1.2.1.43.8.2.1.11.1.2" to int(drawerStatus),
        // indexes 3 and 4: noSuchInstance
        "1.3.6.1.2.1.43.8.2.1.2.1.3" to SnmpValue.NoSuch,
        "1.3.6.1.2.1.43.8.2.1.13.1.3" to SnmpValue.NoSuch
    )

    @Test
    fun parseInputs_idle_measured() {
        val ins = PrinterProbe.parseInputs(inputMap(0, 1, -3, 0))
        assertEquals(2, ins.size)
        assertEquals(PaperInput(1, "Stack Bypass", InputType.MANUAL, 1, false), ins[0])
        assertEquals(PaperInput(2, "Drawer1", InputType.CASSETTE, 150, true), ins[1])
    }

    @Test
    fun parseInputs_trayPulled() {
        val ins = PrinterProbe.parseInputs(inputMap(-3, 0, 0, 1))
        assertEquals(true, ins[0].hasPaper)    // sheet in bypass
        assertEquals(false, ins[1].hasPaper)   // cassette missing/empty
    }

    @Test
    fun parseInputs_levelSemantics() {
        assertEquals(false, PrinterProbe.parseInputs(inputMap(0, 0, 0, 0))[1].hasPaper)    // 0 -> empty
        assertEquals(true, PrinterProbe.parseInputs(inputMap(0, 0, 80, 0))[1].hasPaper)    // >0
        assertNull(PrinterProbe.parseInputs(inputMap(0, 0, -2, 0))[1].hasPaper)            // unknown
        assertNull(PrinterProbe.parseInputs(inputMap(0, 0, -1, 0))[1].hasPaper)            // other
        assertEquals(false, PrinterProbe.parseInputs(inputMap(0, 0, -3, 1))[1].hasPaper)   // status 1 wins
    }

    @Test
    fun parseInputs_typeMapping() {
        fun typeOf(n: Long) = PrinterProbe.parseInputs(
            mapOf("1.3.6.1.2.1.43.8.2.1.2.1.1" to int(n), "1.3.6.1.2.1.43.8.2.1.13.1.1" to str("X"))
        )[0].type
        assertEquals(InputType.MANUAL, typeOf(5))
        assertEquals(InputType.CASSETTE, typeOf(4))
        assertEquals(InputType.CASSETTE, typeOf(3))
        assertEquals(InputType.OTHER, typeOf(6))
    }

    // -------------------------------------------------------------------------
    // Alerts
    // -------------------------------------------------------------------------

    private val A = "1.3.6.1.2.1.43.18.1.1"

    private fun alertRow(idx: String, severity: Long, group: Long, code: Long) = listOf(
        "$A.1.$idx" to int(1),
        "$A.2.$idx" to int(severity),
        "$A.3.$idx" to int(7),
        "$A.4.$idx" to int(group),
        "$A.5.$idx" to int(1),
        "$A.6.$idx" to int(1),
        "$A.7.$idx" to int(code),
        "$A.8.$idx" to str("text")
    )

    /** Real walk is column-major (all .2 rows, then .4 ...) — emulate by sorting on column. */
    private fun columnMajor(vararg rows: List<Pair<String, SnmpValue>>) =
        rows.flatMap { it }.sortedBy { it.first.removePrefix("$A.").substringBefore('.').toInt() }

    @Test
    fun parseAlerts_trayPulled_808() {
        val alerts = PrinterProbe.parseAlerts(columnMajor(alertRow("1.1", 4, 5, 808)))
        assertEquals(listOf(PrinterAlert(AlertKind.PAPER_EMPTY, 808, 5, 4)), alerts)
        assertFalse(alerts[0].critical)
    }

    @Test
    fun parseAlerts_coverOpen_3_critical() {
        val alerts = PrinterProbe.parseAlerts(columnMajor(alertRow("1.1", 3, 5, 3)))
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.COVER_OPEN, alerts[0].kind)
        assertTrue(alerts[0].critical)
    }

    @Test
    fun parseAlerts_multipleRows_columnMajorWalk() {
        val alerts = PrinterProbe.parseAlerts(
            columnMajor(alertRow("1.1", 4, 5, 808), alertRow("1.2", 3, 5, 3))
        )
        assertEquals(listOf(AlertKind.PAPER_EMPTY, AlertKind.COVER_OPEN), alerts.map { it.kind })
        assertEquals(listOf(4, 3), alerts.map { it.severity })
    }

    @Test
    fun parseAlerts_emptyAndForeignOids() {
        assertTrue(PrinterProbe.parseAlerts(emptyList()).isEmpty())
        assertTrue(PrinterProbe.parseAlerts(listOf("1.3.6.1.2.1.1.1.0" to int(1))).isEmpty())
    }

    @Test
    fun parseAlerts_leadingDotAndRowWithoutCodeSkipped() {
        val walk = listOf(
            ".$A.2.1.1" to int(4),
            ".$A.4.1.1" to int(5),
            ".$A.7.1.1" to int(807),
            ".$A.2.1.2" to int(4)          // row 1.2 has no code column
        )
        assertEquals(listOf(AlertKind.PAPER_LOW), PrinterProbe.parseAlerts(walk).map { it.kind })
    }

    @Test
    fun alertKindOf_rfc3805Codes() {
        assertEquals(AlertKind.COVER_OPEN, PrinterProbe.alertKindOf(5, 3))
        assertEquals(AlertKind.COVER_OPEN, PrinterProbe.alertKindOf(6, 5))      // interlockOpen
        assertEquals(AlertKind.JAM, PrinterProbe.alertKindOf(13, 8))
        assertEquals(AlertKind.TRAY_MISSING, PrinterProbe.alertKindOf(8, 801))
        assertEquals(AlertKind.PAPER_EMPTY, PrinterProbe.alertKindOf(5, 808))   // Canon group 5
        assertEquals(AlertKind.PAPER_LOW, PrinterProbe.alertKindOf(8, 807))
        assertEquals(AlertKind.TONER_EMPTY, PrinterProbe.alertKindOf(11, 1101))
        assertEquals(AlertKind.TONER_LOW, PrinterProbe.alertKindOf(11, 1104))
        assertEquals(AlertKind.OTHER, PrinterProbe.alertKindOf(5, 4))           // coverClosed
        assertEquals(AlertKind.OTHER, PrinterProbe.alertKindOf(5, 507))
    }

    @Test
    fun alertKindOf_genericSubunitCodesUseGroup() {
        assertEquals(AlertKind.PAPER_EMPTY, PrinterProbe.alertKindOf(8, 13))
        assertEquals(AlertKind.TONER_EMPTY, PrinterProbe.alertKindOf(11, 13))
        assertEquals(AlertKind.PAPER_LOW, PrinterProbe.alertKindOf(8, 12))
        assertEquals(AlertKind.TONER_LOW, PrinterProbe.alertKindOf(11, 12))
        assertEquals(AlertKind.OTHER, PrinterProbe.alertKindOf(5, 13))
    }

    // -------------------------------------------------------------------------
    // Duplex / model
    // -------------------------------------------------------------------------

    private val MP = "1.3.6.1.2.1.43.13.4.1.9.1"

    @Test
    fun parseDuplex_simplexOnly_isFalse() {
        assertEquals(false, PrinterProbe.parseDuplex(mapOf("$MP.1" to int(5))))
    }

    @Test
    fun parseDuplex_longOrShortEdge_isTrue() {
        assertEquals(true, PrinterProbe.parseDuplex(mapOf("$MP.1" to int(3))))
        assertEquals(true, PrinterProbe.parseDuplex(mapOf("$MP.1" to int(5), "$MP.2" to int(4))))
    }

    @Test
    fun parseDuplex_missing_isNull() {
        assertNull(PrinterProbe.parseDuplex(emptyMap()))
        assertNull(PrinterProbe.parseDuplex(mapOf("$MP.1" to SnmpValue.NoSuch)))
    }

    @Test
    fun cleanModel_dropsLanguageSuffix() {
        assertEquals("Canon MF8030", PrinterProbe.cleanModel("Canon MF8030 /P"))
        assertEquals("Canon MF8030", PrinterProbe.cleanModel("Canon MF8030"))
    }

    // -------------------------------------------------------------------------
    // MFNP
    // -------------------------------------------------------------------------

    @Test
    fun mfnpGetIdPacket_matchesPythonStructPack() {
        // struct.pack(">4sBBHHHI", b"MFNP", 2, 0x30, 0, 1, 0, 0)
        val expected = byteArrayOf(
            0x4D, 0x46, 0x4E, 0x50, 0x02, 0x30, 0x00, 0x00,
            0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        assertArrayEquals(expected, PrinterProbe.mfnpGetIdPacket(1))
    }

    @Test
    fun parseMfnpId_stripsHeader() {
        val header = ByteArray(16) { if (it < 4) "MFNP"[it].code.toByte() else 0 }
        val id = "MFG:Canon;CMD:MFNP;CLS:IMG;MDL:MF8030;"
        assertEquals(id, PrinterProbe.parseMfnpId(header + id.toByteArray()))
        assertNull(PrinterProbe.parseMfnpId(header))
        assertNull(PrinterProbe.parseMfnpId(ByteArray(5)))
    }
}
