package cz.ufrii.print.status

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

// ---------------------------------------------------------------------------
// Model
// ---------------------------------------------------------------------------

/** Toner colour, derived from the supply description text (NOT from the index — Canon order is K,Y,M,C). */
enum class TonerColor { BLACK, CYAN, MAGENTA, YELLOW, OTHER }

/**
 * One toner supply. [levelPercent] is null when the printer reports "unknown" (-2) or
 * "some remaining" (-3). [searchText] is a string suitable for searching a shop for the cartridge.
 */
data class Toner(
    val index: Int,
    val color: TonerColor,
    val description: String,
    val levelPercent: Int?,
    val searchText: String
)

/** Paper input kind (prtInputType). */
enum class InputType { MANUAL, CASSETTE, OTHER }

/** One paper input (bypass tray, drawer). [hasPaper] null = unknown. */
data class PaperInput(
    val index: Int,
    val name: String,
    val type: InputType,
    val maxCapacity: Int?,
    val hasPaper: Boolean?
)

/** Coarse alert category derived from the RFC 3805 prtAlertCode. */
enum class AlertKind {
    COVER_OPEN, JAM, PAPER_EMPTY, TRAY_MISSING, PAPER_LOW, TONER_EMPTY, TONER_LOW, OTHER
}

/** One active row of prtAlertTable. [severity]: 3 critical, 4 warning, 5 warningBinaryChangeEvent. */
data class PrinterAlert(val kind: AlertKind, val code: Int, val group: Int, val severity: Int) {
    /** True when the printer itself classifies the alert as critical (printer cannot print). */
    val critical: Boolean get() = severity == 3
}

/** Live status snapshot. [reachable] = false when SNMP did not answer at all. */
data class PrinterStatus(
    val reachable: Boolean,
    val toners: List<Toner>,
    val inputs: List<PaperInput>,
    val alerts: List<PrinterAlert>,
    val deviceStatus: Int?,
    val pageCount: Long?,
    val timestamp: Long
)

/** Printer capabilities, cached per printer. `null` = not known / not detected. */
data class PrinterCapabilities(
    val sysDescr: String?,
    val model: String?,
    val color: Boolean?,
    val inputs: List<PaperInput>,
    val duplex: Boolean?,
    val scanner: Boolean?,
    val adf: Boolean?,
    val snmp: Boolean,
    val detectedAt: Long
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("sysDescr", sysDescr ?: JSONObject.NULL)
        o.put("model", model ?: JSONObject.NULL)
        o.put("color", color ?: JSONObject.NULL)
        o.put("duplex", duplex ?: JSONObject.NULL)
        o.put("scanner", scanner ?: JSONObject.NULL)
        o.put("adf", adf ?: JSONObject.NULL)
        o.put("snmp", snmp)
        o.put("detectedAt", detectedAt)
        val arr = JSONArray()
        for (i in inputs) {
            arr.put(
                JSONObject()
                    .put("index", i.index)
                    .put("name", i.name)
                    .put("type", i.type.name)
                    .put("maxCapacity", i.maxCapacity ?: JSONObject.NULL)
                    .put("hasPaper", i.hasPaper ?: JSONObject.NULL)
            )
        }
        o.put("inputs", arr)
        return o
    }

    companion object {
        /** Inverse of [toJson]; null when the JSON is malformed. */
        fun fromJson(o: JSONObject): PrinterCapabilities? = try {
            val inputs = ArrayList<PaperInput>()
            val arr = o.optJSONArray("inputs")
            if (arr != null) {
                for (n in 0 until arr.length()) {
                    val j = arr.getJSONObject(n)
                    inputs.add(
                        PaperInput(
                            index = j.getInt("index"),
                            name = j.getString("name"),
                            type = runCatching { InputType.valueOf(j.getString("type")) }
                                .getOrDefault(InputType.OTHER),
                            maxCapacity = if (j.isNull("maxCapacity")) null else j.getInt("maxCapacity"),
                            hasPaper = if (j.isNull("hasPaper")) null else j.getBoolean("hasPaper")
                        )
                    )
                }
            }
            PrinterCapabilities(
                sysDescr = if (o.isNull("sysDescr")) null else o.getString("sysDescr"),
                model = if (o.isNull("model")) null else o.getString("model"),
                color = if (o.isNull("color")) null else o.getBoolean("color"),
                inputs = inputs,
                duplex = if (o.isNull("duplex")) null else o.getBoolean("duplex"),
                scanner = if (o.isNull("scanner")) null else o.getBoolean("scanner"),
                adf = if (o.isNull("adf")) null else o.getBoolean("adf"),
                snmp = o.getBoolean("snmp"),
                detectedAt = o.getLong("detectedAt")
            )
        } catch (e: Exception) {
            null
        }
    }
}

// ---------------------------------------------------------------------------
// Probe
// ---------------------------------------------------------------------------

/**
 * Reads printer capabilities and status via SNMP (docs/21-stav-a-schopnosti-tiskarny.md).
 *
 * All traffic is unicast to the printer IP: SNMP GET/GETNEXT on UDP 161 and one MFNP GET_ID
 * datagram on UDP 8610. Never broadcast, never TCP to 8610-8612 (MF8030Cn firmware wedges).
 * Fixed OIDs are fetched with multi-varbind GETs (< 1400 B per packet); the only walk is
 * prtAlertTable.
 */
object PrinterProbe {

    // OIDs (no leading dot — the Snmp client normalises keys the same way)
    private const val OID_SYS_DESCR = "1.3.6.1.2.1.1.1.0"
    private const val OID_HR_DEVICE_DESCR = "1.3.6.1.2.1.25.3.2.1.3.1"
    private const val OID_HR_DEVICE_STATUS = "1.3.6.1.2.1.25.3.2.1.5.1"
    private const val OID_PAGE_COUNT = "1.3.6.1.2.1.43.10.2.1.4.1.1"
    /** prtMarkerSuppliesEntry: .6 description, .8 max capacity, .9 level; index .1.i */
    private const val OID_SUPPLY = "1.3.6.1.2.1.43.11.1.1"
    /** prtInputEntry: .2 type, .9 max capacity, .10 level, .11 status, .13 name; index .1.i */
    private const val OID_INPUT = "1.3.6.1.2.1.43.8.2.1"
    /** prtMediaPathType; index .1.k */
    private const val OID_MEDIA_PATH_TYPE = "1.3.6.1.2.1.43.13.4.1.9"
    private const val OID_ALERT_TABLE = "1.3.6.1.2.1.43.18.1.1"

    private const val MAX_TONERS = 6
    private const val MAX_INPUTS = 4
    private const val MAX_MEDIA_PATHS = 4
    private const val ALERT_WALK_LIMIT = 60

    private const val MFNP_PORT = 8610
    private const val MFNP_CMD_GET_ID = 0x30
    private const val MFNP_DEV_TYPE_SCANNER = 2

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** TCP connect test (default: raw print port 9100). */
    suspend fun tcpReachable(ip: String, port: Int = 9100, timeoutMs: Int = 3000): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
                true
            } catch (e: Exception) {
                false
            }
        }

    /** Reads toners, inputs, alerts, device status and page counter. reachable=false if SNMP is silent. */
    suspend fun readStatus(ip: String): PrinterStatus {
        val now = System.currentTimeMillis()
        val core = Snmp.get(ip, statusCoreOids())
            ?: return PrinterStatus(false, emptyList(), emptyList(), emptyList(), null, null, now)
        val inputMap = Snmp.get(ip, inputOids()) ?: emptyMap()
        val alerts = Snmp.walk(ip, OID_ALERT_TABLE, ALERT_WALK_LIMIT)
        return PrinterStatus(
            reachable = true,
            toners = parseToners(core),
            inputs = parseInputs(inputMap),
            alerts = parseAlerts(alerts),
            deviceStatus = intOf(core, OID_HR_DEVICE_STATUS)?.toInt(),
            pageCount = intOf(core, OID_PAGE_COUNT),
            timestamp = now
        )
    }

    /**
     * Detects model, colour, inputs, duplex (SNMP) and scanner (MFNP GET_ID).
     * [PrinterCapabilities.adf] is always null here — detected later by a paper test.
     */
    suspend fun probeCapabilities(ip: String): PrinterCapabilities {
        val core = Snmp.get(ip, capabilityCoreOids())
        val inputMap = if (core != null) Snmp.get(ip, inputOids()) ?: emptyMap() else emptyMap()
        val id = mfnpIdentify(ip)
        val scanner: Boolean? = id?.let { it.contains("CLS:IMG", ignoreCase = true) }

        if (core == null) {
            return PrinterCapabilities(
                sysDescr = null, model = null, color = null, inputs = emptyList(), duplex = null,
                scanner = scanner, adf = null, snmp = false, detectedAt = System.currentTimeMillis()
            )
        }
        val sysDescr = strOf(core, OID_SYS_DESCR)
        val model = strOf(core, OID_HR_DEVICE_DESCR)?.takeIf { it.isNotBlank() }
            ?: sysDescr?.let { cleanModel(it) }?.takeIf { it.isNotBlank() }
        return PrinterCapabilities(
            sysDescr = sysDescr,
            model = model,
            color = isColor(parseToners(core)),
            inputs = parseInputs(inputMap),
            duplex = parseDuplex(core),
            scanner = scanner,
            adf = null,
            snmp = true,
            detectedAt = System.currentTimeMillis()
        )
    }

    /**
     * UDP unicast MFNP GET_ID to ip:8610 (cmd 0x30, dev_type 2, 16-byte big-endian header, see
     * scripts/mfnp_probe.py). Returns the IEEE-1284 ID string or null.
     * NEVER broadcast, NEVER TCP.
     */
    suspend fun mfnpIdentify(ip: String, timeoutMs: Int = 2000): String? =
        withContext(Dispatchers.IO) {
            try {
                val addr = InetAddress.getByName(ip)
                if (addr.isMulticastAddress || addr.isAnyLocalAddress ||
                    addr.hostAddress == "255.255.255.255"
                ) return@withContext null
                DatagramSocket().use { sock ->
                    sock.soTimeout = timeoutMs
                    sock.connect(InetSocketAddress(addr, MFNP_PORT))
                    val req = mfnpGetIdPacket(seq = 1)
                    sock.send(DatagramPacket(req, req.size))
                    val buf = ByteArray(2048)
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    parseMfnpId(buf.copyOf(pkt.length))
                }
            } catch (e: Exception) {
                null
            }
        }

    // -------------------------------------------------------------------------
    // OID batches
    // -------------------------------------------------------------------------

    private fun supplyOid(column: Int, index: Int) = "$OID_SUPPLY.$column.1.$index"
    private fun inputOid(column: Int, index: Int) = "$OID_INPUT.$column.1.$index"

    /** device status, page counter, toner description/max/level — 2 + 3*6 = 20 varbinds. */
    private fun statusCoreOids(): List<String> = buildList {
        add(OID_HR_DEVICE_STATUS)
        add(OID_PAGE_COUNT)
        for (i in 1..MAX_TONERS) {
            add(supplyOid(6, i)); add(supplyOid(8, i)); add(supplyOid(9, i))
        }
    }

    /** sysDescr, hrDeviceDescr, toner descriptions, media path types — 2 + 6 + 4 = 12 varbinds. */
    private fun capabilityCoreOids(): List<String> = buildList {
        add(OID_SYS_DESCR)
        add(OID_HR_DEVICE_DESCR)
        for (i in 1..MAX_TONERS) add(supplyOid(6, i))
        for (k in 1..MAX_MEDIA_PATHS) add("$OID_MEDIA_PATH_TYPE.1.$k")
    }

    /** type, name, max capacity, level, status for inputs 1..4 — 20 varbinds. */
    private fun inputOids(): List<String> = buildList {
        for (i in 1..MAX_INPUTS) {
            add(inputOid(2, i)); add(inputOid(13, i)); add(inputOid(9, i))
            add(inputOid(10, i)); add(inputOid(11, i))
        }
    }

    // -------------------------------------------------------------------------
    // Pure parse helpers (internal for tests)
    // -------------------------------------------------------------------------

    private fun norm(map: Map<String, SnmpValue>): Map<String, SnmpValue> =
        if (map.keys.any { it.startsWith(".") }) map.mapKeys { it.key.trimStart('.') } else map

    private fun intOf(map: Map<String, SnmpValue>, oid: String): Long? =
        (map[oid] as? SnmpValue.Int)?.v

    private fun strOf(map: Map<String, SnmpValue>, oid: String): String? =
        (map[oid] as? SnmpValue.Str)?.text()?.trim()

    /** Toners from the fixed-OID answer; indexes without a description are skipped. */
    internal fun parseToners(map: Map<String, SnmpValue>): List<Toner> {
        val m = norm(map)
        val out = ArrayList<Toner>()
        for (i in 1..MAX_TONERS) {
            val desc = strOf(m, supplyOid(6, i)) ?: continue
            if (desc.isEmpty()) continue
            val level = intOf(m, supplyOid(9, i))
            val max = intOf(m, supplyOid(8, i))
            val percent: Int? = when {
                level == null || level < 0 -> null           // -2 unknown, -3 "some remaining"
                max != null && max > 0 -> (level * 100 / max).toInt().coerceIn(0, 100)
                else -> null
            }
            out.add(Toner(i, tonerColorOf(desc), desc, percent, searchTextOf(desc)))
        }
        return out
    }

    /** Colour from description text (case-insensitive); never from the index. */
    internal fun tonerColorOf(desc: String): TonerColor {
        val d = desc.lowercase()
        return when {
            "black" in d -> TonerColor.BLACK
            "cyan" in d -> TonerColor.CYAN
            "magenta" in d -> TonerColor.MAGENTA
            "yellow" in d -> TonerColor.YELLOW
            else -> TonerColor.OTHER
        }
    }

    private val CRG_REGEX = Regex("CRG\\s?-?3(\\d\\d)", RegexOption.IGNORE_CASE)

    /**
     * Shop search text. Canon JP naming CRG-3xx corresponds to EU "Cartridge 7xx":
     * "Canon CRG316 Yellow Toner" -> "Canon Cartridge 716 Yellow (CRG-316)".
     * Other descriptions are returned trimmed.
     */
    internal fun searchTextOf(desc: String): String {
        val m = CRG_REGEX.find(desc) ?: return desc.trim()
        val nn = m.groupValues[1]
        val color = when (tonerColorOf(desc)) {
            TonerColor.BLACK -> "Black"
            TonerColor.CYAN -> "Cyan"
            TonerColor.MAGENTA -> "Magenta"
            TonerColor.YELLOW -> "Yellow"
            TonerColor.OTHER -> ""
        }
        val colorPart = if (color.isEmpty()) "" else "$color "
        return "Canon Cartridge 7$nn $colorPart(CRG-3$nn)"
    }

    /** Colour printer if any toner is C/M/Y; mono if toners exist but none; null with no toners. */
    internal fun isColor(toners: List<Toner>): Boolean? =
        if (toners.isEmpty()) null
        else toners.any {
            it.color == TonerColor.CYAN || it.color == TonerColor.MAGENTA || it.color == TonerColor.YELLOW
        }

    /** Paper inputs 1..4 from the fixed-OID answer; indexes with neither type nor name are skipped. */
    internal fun parseInputs(map: Map<String, SnmpValue>): List<PaperInput> {
        val m = norm(map)
        val out = ArrayList<PaperInput>()
        for (i in 1..MAX_INPUTS) {
            val type = intOf(m, inputOid(2, i))
            val name = strOf(m, inputOid(13, i))
            if (type == null && name == null) continue
            val maxCap = intOf(m, inputOid(9, i))?.takeIf { it >= 0 }?.toInt()
            val level = intOf(m, inputOid(10, i))
            val status = intOf(m, inputOid(11, i))
            val hasPaper: Boolean? = when {
                status == 1L -> false
                level == null -> null
                level == 0L -> false
                level == -3L || level > 0L -> true
                else -> null                                  // -1 other, -2 unknown
            }
            out.add(
                PaperInput(
                    index = i,
                    name = name?.takeIf { it.isNotEmpty() } ?: "Input $i",
                    type = when (type) {
                        5L -> InputType.MANUAL
                        3L, 4L -> InputType.CASSETTE
                        else -> InputType.OTHER
                    },
                    maxCapacity = maxCap,
                    hasPaper = hasPaper
                )
            )
        }
        return out
    }

    /** prtMediaPathType: 3/4 = long/short-edge duplex -> true; 5 = simplex only -> false; else null. */
    internal fun parseDuplex(map: Map<String, SnmpValue>): Boolean? {
        val m = norm(map)
        val values = (1..MAX_MEDIA_PATHS).mapNotNull { intOf(m, "$OID_MEDIA_PATH_TYPE.1.$it") }
        return when {
            values.any { it == 3L || it == 4L } -> true
            values.any { it == 5L } -> false
            else -> null
        }
    }

    /**
     * Active alerts from a walk of prtAlertTable (.43.18.1.1). Columns: .2 severity, .4 group,
     * .7 code; rows keyed by the index suffix after the column. Rows without a code are skipped.
     */
    internal fun parseAlerts(walk: List<Pair<String, SnmpValue>>): List<PrinterAlert> {
        class Row { var severity = 0; var group = 0; var code: Int? = null }
        val rows = LinkedHashMap<String, Row>()
        val prefix = "$OID_ALERT_TABLE."
        for ((rawOid, value) in walk) {
            val oid = rawOid.trimStart('.')
            if (!oid.startsWith(prefix)) continue
            val rest = oid.removePrefix(prefix)
            val dot = rest.indexOf('.')
            if (dot < 0) continue
            val column = rest.substring(0, dot).toIntOrNull() ?: continue
            val key = rest.substring(dot + 1)
            val v = (value as? SnmpValue.Int)?.v?.toInt() ?: continue
            val row = rows.getOrPut(key) { Row() }
            when (column) {
                2 -> row.severity = v
                4 -> row.group = v
                7 -> row.code = v
            }
        }
        return rows.values.mapNotNull { r ->
            val code = r.code ?: return@mapNotNull null
            PrinterAlert(alertKindOf(r.group, code), code, r.group, r.severity)
        }
    }

    /**
     * RFC 3805 PrtAlertCodeTC -> [AlertKind]. Codes: coverOpen 3, interlockOpen 5, jam 8,
     * inputMediaTrayMissing 801, inputMediaSupplyLow 807, inputMediaSupplyEmpty 808,
     * markerTonerEmpty 1101, markerTonerAlmostEmpty 1104. [group] (PrtAlertGroupTC: input 8,
     * markerSupplies 11) only disambiguates the generic subunit codes 9/12/13. Canon reports
     * non-standard group numbers for the specific codes (808 comes with group 5), so those are
     * matched by code alone.
     */
    internal fun alertKindOf(group: Int, code: Int): AlertKind = when (code) {
        3, 5, 501 -> AlertKind.COVER_OPEN                     // 501 doorOpen (deprecated)
        8 -> AlertKind.JAM
        801 -> AlertKind.TRAY_MISSING
        808 -> AlertKind.PAPER_EMPTY
        807 -> AlertKind.PAPER_LOW
        1101 -> AlertKind.TONER_EMPTY
        1104 -> AlertKind.TONER_LOW
        9 -> if (group == 8) AlertKind.TRAY_MISSING else AlertKind.OTHER
        12 -> when (group) { 8 -> AlertKind.PAPER_LOW; 11 -> AlertKind.TONER_LOW; else -> AlertKind.OTHER }
        13 -> when (group) { 8 -> AlertKind.PAPER_EMPTY; 11 -> AlertKind.TONER_EMPTY; else -> AlertKind.OTHER }
        else -> AlertKind.OTHER
    }

    /** "Canon MF8030 /P" -> "Canon MF8030" (drops the trailing "/P"-style language suffix). */
    internal fun cleanModel(sysDescr: String): String =
        sysDescr.substringBefore(';').replace(Regex("\\s*/\\w{1,3}\\s*$"), "").trim()

    // -------------------------------------------------------------------------
    // MFNP GET_ID
    // -------------------------------------------------------------------------

    /**
     * 16-byte MFNP header, big-endian: "MFNP"(4) dev_type(1) cmd(1) unk(2) seq(2) session(2)
     * payload_len(4) — same as scripts/mfnp_probe.py `pkt(b"MFNP", 0x30, seq)`.
     */
    internal fun mfnpGetIdPacket(seq: Int): ByteArray =
        ByteBuffer.allocate(16)
            .put("MFNP".toByteArray(Charsets.US_ASCII))
            .put(MFNP_DEV_TYPE_SCANNER.toByte())
            .put(MFNP_CMD_GET_ID.toByte())
            .putShort(0)
            .putShort(seq.toShort())
            .putShort(0)
            .putInt(0)
            .array()

    /** IEEE-1284 ID text following the 16-byte header (MFNP carries ASCII directly). */
    internal fun parseMfnpId(reply: ByteArray): String? {
        if (reply.size <= 16) return null
        val text = String(reply, 16, reply.size - 16, Charsets.ISO_8859_1)
            .trim { it <= ' ' }
        return text.takeIf { it.isNotEmpty() }
    }
}
