package cz.ufrii.print

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/**
 * What one Canon UFR-II PPD says, from assets/ppd-index.json (scripts/build-ppd-pack.py):
 * the model names, which trays ([slots]) and options exist, and the A4 printable area.
 */
data class PpdInfo(
    val file: String,
    val short: String,
    val nick: String?,
    val color: Boolean,
    val aliases: List<String>,
    /** "MF4320-4350"-style families: (prefix, lowest, highest model number). */
    val ranges: List<Triple<String, Int, Int>>,
    /** InputSlot choices, e.g. Auto, Manual, Cas1, Cas2. */
    val slots: List<String>,
    /** The model has a duplex unit option (Duplex=DuplexNoTumble). */
    val duplex: Boolean,
    /** CNColorMode choices; empty = mono-only PPD without the option. */
    val colorModes: List<String>,
    /** A4 ImageableArea in points: left, bottom, right, top. */
    val a4: FloatArray
) {
    /** Paper sources the app can offer for this model, in PPD order. */
    val sources: List<PaperSource> get() = slots.mapNotNull { PaperSource.fromPpdOrNull(it) }.ifEmpty { listOf(PaperSource.AUTO) }
}

/**
 * Raster page size for the filter. Every PPD takes RGB 8-bit chunky at 600 dpi (checked for all
 * 414 by running rastertoufr2), but the A4 printable area differs: 403 PPDs use the golden header
 * (4724 × 6780), a few older ones a slightly smaller area.
 */
data class PageGeometry(val width: Int, val height: Int, val area: FloatArray) {
    val bytesPerLine: Int get() = width * 3
    val isDefault: Boolean get() = width == DEFAULT.width && height == DEFAULT.height

    companion object {
        private val GOLDEN_AREA = floatArrayOf(14.173f, 14.173f, 581.127f, 827.727f)
        val DEFAULT = PageGeometry(4724, 6780, GOLDEN_AREA)

        /**
         * Size relative to the golden header (whose numbers the filter accepted), so the
         * rounding matches it: only the difference in points is converted to pixels.
         */
        fun fromArea(a: FloatArray): PageGeometry {
            val dw = (a[2] - a[0]) - (GOLDEN_AREA[2] - GOLDEN_AREA[0])
            val dh = (a[3] - a[1]) - (GOLDEN_AREA[3] - GOLDEN_AREA[1])
            if (kotlin.math.abs(dw) < 0.05f && kotlin.math.abs(dh) < 0.05f) return DEFAULT
            return PageGeometry(DEFAULT.width + (dw * 600f / 72f).roundToInt(),
                DEFAULT.height + (dh * 600f / 72f).roundToInt(), a)
        }
    }
}

object PpdCatalog {

    /** What the app printed with before it knew other models (MF8030Cn). */
    const val DEFAULT_FILE = "CNRCUPSMF8000CZK.ppd"

    @Volatile private var cache: List<PpdInfo>? = null

    fun all(ctx: Context): List<PpdInfo> = cache ?: synchronized(this) {
        cache ?: load(ctx).also { cache = it }
    }

    fun byFile(ctx: Context, file: String?): PpdInfo =
        all(ctx).let { list -> list.firstOrNull { it.file == file } ?: list.first { it.file == DEFAULT_FILE } }

    fun forPrinter(ctx: Context, p: Printer): PpdInfo = byFile(ctx, p.ppd)

    /**
     * Two-sided printing by the printer itself: the model has a duplex unit option and SNMP did
     * not report a simplex-only paper path. Otherwise duplex is manual (two passes, [ManualDuplexJob]).
     */
    fun autoDuplex(ctx: Context, p: Printer): Boolean = forPrinter(ctx, p).duplex && p.capabilities?.duplex != false

    /**
     * Picks the PPD for what the printer reports, tried in order (MFNP MDL first — "MF8000 Series"
     * equals a PPD name exactly — then SNMP hrDeviceDescr / sysDescr, then the user's name).
     * For each text: exact alias, without the letter suffix (MF8030Cn → MF8030), with/without
     * "C", a family range, then the series (MF8030 → MF8000). [color] breaks ties. Null = no idea.
     */
    fun match(ctx: Context, reported: List<String?>, color: Boolean?): PpdInfo? {
        val list = all(ctx)
        for (text in reported.filterNotNull().filter { it.isNotBlank() }) {
            for (key in keys(norm(text))) {
                val hits = list.filter { key in it.aliases || inRange(it, key) }
                if (hits.isNotEmpty()) return hits.firstOrNull { color == null || it.color == color } ?: hits.first()
            }
        }
        return null
    }

    /** Same normalisation as build-ppd-pack.py: upper case, no vendor/series words, no spaces. */
    fun norm(s: String): String {
        var t = s.uppercase()
        for (w in listOf("CANON", "I-SENSYS", "ISENSYS", "IMAGECLASS", "IMAGERUNNER", "SERIES", "UFRII", "UFR II", "LT")) {
            t = t.replace(w, " ")
        }
        // "MF8030 /P" language suffix (SNMP sysDescr).
        t = t.replace(Regex("/[A-Z]{1,3}\\s*$"), "")
        return t.replace(Regex("[^A-Z0-9-]"), "")
    }

    private val MODEL = Regex("^([A-Z-]*?)(\\d+)([A-Z]*)$")

    private fun keys(n: String): List<String> {
        val m = MODEL.find(n) ?: return listOf(n)
        val (prefix, digits, suffix) = m.destructured
        val num = digits.toInt()
        val series = (num / 100) * 100
        return listOf(
            n, "$prefix$digits", "$prefix${digits}C", "$prefix$digits${suffix.take(1)}",
            "$prefix$series", "$prefix${series}C"
        ).distinct().filter { series > 0 || !it.startsWith("$prefix$series") }
    }

    private fun inRange(p: PpdInfo, key: String): Boolean {
        val m = MODEL.find(key) ?: return false
        val num = m.groupValues[2].toInt()
        return p.ranges.any { (prefix, lo, hi) -> prefix == m.groupValues[1] && num in lo..hi }
    }

    /**
     * The PPD inside the rootfs for the filter's PPD= (guest path). Extracted from ppd.zip on
     * first use; the rootfs is wiped on version change, so this re-creates it as needed.
     */
    fun guestPath(ctx: Context, rootfsDir: File, info: PpdInfo): String {
        val dir = File(rootfsDir, "etc/cups/ppd").apply { mkdirs() }
        val f = File(dir, info.file)
        if (!f.exists() || f.length() == 0L) {
            ZipInputStream(ctx.assets.open("ppd.zip").buffered()).use { z ->
                var e = z.nextEntry
                while (e != null && e.name != info.file) e = z.nextEntry
                requireNotNull(e) { "${info.file} chybí v ppd.zip" }
                val tmp = File(dir, "${info.file}.tmp")
                tmp.outputStream().use { z.copyTo(it) }
                tmp.renameTo(f)
            }
        }
        return "/etc/cups/ppd/${info.file}"
    }

    private fun load(ctx: Context): List<PpdInfo> {
        val arr = JSONArray(ctx.assets.open("ppd-index.json").bufferedReader().readText())
        fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ranges = o.optJSONArray("ranges")?.let { r ->
                (0 until r.length()).map { j -> r.getJSONArray(j).let { Triple(it.getString(0), it.getInt(1), it.getInt(2)) } }
            } ?: emptyList()
            val a4 = o.optJSONArray("a4")?.let { a -> FloatArray(4) { a.getDouble(it).toFloat() } }
                ?: floatArrayOf(14.173f, 14.173f, 581.127f, 827.727f)
            PpdInfo(
                file = o.getString("file"),
                short = o.getString("short"),
                nick = o.optString("nick").takeIf { it.isNotBlank() },
                color = o.optBoolean("color"),
                aliases = strings(o.optJSONArray("aliases")),
                ranges = ranges,
                slots = strings(o.optJSONArray("slots")),
                duplex = o.optBoolean("duplex"),
                colorModes = strings(o.optJSONArray("colorModes")),
                a4 = a4
            )
        }
    }
}
