package cz.ufrii.print

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

private const val TAG = "CanonPrint"

enum class ScanSource(val sane: String) { GLASS("Flatbed"), FEEDER("Automatic Document Feeder") }
enum class ScanMode(val sane: String) { COLOR("Color"), GRAY("Gray"), LINEART("Lineart") }

/** Where a finished scan goes: Downloads/UFR-II, a place picked in the system picker, share sheet, or print a copy. */
enum class ScanDest { DOWNLOADS, CHOOSE, SHARE, PRINT }
/** Output file type; JPEG and PNG are one image per page. */
enum class ScanFormat(val mime: String, val ext: String) {
    PDF("application/pdf", "pdf"), JPEG("image/jpeg", "jpg"), PNG("image/png", "png");
    val isImage: Boolean get() = this != PDF
}

/** What the scanner itself reports (`scanimage -A`), cached per printer. */
data class ScanCapabilities(
    val sources: List<ScanSource>,
    val modes: List<ScanMode>,
    val resolutions: List<Int>,
    val detectedAt: Long = System.currentTimeMillis(),
    /** Glass size in mm (`-x` / `-y` ranges); null = the scanner cannot scan a part of the glass. */
    val glassW: Float? = null,
    val glassH: Float? = null,
    /** SANE device name from `scanimage -L` (pixma:<model>_<IPv4> or pixma:<model>_<MAC> over IPv6). */
    val device: String? = null
) {
    val canCrop: Boolean get() = glassW != null && glassH != null

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("sources", org.json.JSONArray(sources.map { it.name }))
        .put("modes", org.json.JSONArray(modes.map { it.name }))
        .put("dpi", org.json.JSONArray(resolutions))
        .put("t", detectedAt)
        .apply { if (glassW != null && glassH != null) put("gw", glassW.toDouble()).put("gh", glassH.toDouble()) }
        .apply { device?.let { put("device", it) } }

    companion object {
        fun fromJson(o: org.json.JSONObject): ScanCapabilities? = runCatching {
            fun names(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
            ScanCapabilities(
                names("sources").mapNotNull { n -> ScanSource.entries.firstOrNull { it.name == n } },
                names("modes").mapNotNull { n -> ScanMode.entries.firstOrNull { it.name == n } },
                o.getJSONArray("dpi").let { a -> (0 until a.length()).map { a.getInt(it) } },
                o.optLong("t"),
                o.optDouble("gw").takeIf { !it.isNaN() }?.toFloat(),
                o.optDouble("gh").takeIf { !it.isNaN() }?.toFloat(),
                o.optString("device").takeIf { it.isNotBlank() }
            )
        }.getOrNull()

        /**
         * Parses `scanimage -A` lines such as
         *   `--mode Color|Gray|Lineart [Color]`, `--source Flatbed|Automatic Document Feeder [Flatbed]`,
         *   `--resolution 75|150|300|600dpi [75]` (or a range `75..600dpi`, then the standard steps inside it),
         *   `-x auto|0..216.747mm [216.747]` / `-y …` (scan-area width/height → glass size).
         */
        fun parse(text: String): ScanCapabilities? {
            fun values(opt: String): List<String>? {
                val line = text.lines().map { it.trim() }.firstOrNull { it.startsWith("$opt ") } ?: return null
                return line.removePrefix(opt).substringBefore(" [").trim().split('|').map { it.trim() }.filter { it.isNotEmpty() }
            }
            val sources = values("--source")?.mapNotNull { v -> ScanSource.entries.firstOrNull { it.sane.equals(v, true) } }
                ?: listOf(ScanSource.GLASS)
            val modes = values("--mode")?.mapNotNull { v -> ScanMode.entries.firstOrNull { it.sane.equals(v, true) } }
                ?: return null
            val resRaw = values("--resolution") ?: return null
            val dpi = if (resRaw.size == 1 && ".." in resRaw[0]) {
                val (lo, hi) = resRaw[0].removeSuffix("dpi").split("..").map { it.trim().toInt() }
                listOf(75, 100, 150, 200, 300, 600, 1200).filter { it in lo..hi }
            } else resRaw.mapNotNull { it.removeSuffix("dpi").trim().toIntOrNull() }
            if (modes.isEmpty() || dpi.isEmpty()) return null
            fun maxMm(opt: String) = values(opt)?.firstOrNull { it.endsWith("mm") && ".." in it }
                ?.removeSuffix("mm")?.substringAfter("..")?.trim()?.toFloatOrNull()
            return ScanCapabilities(sources, modes, dpi.sorted(), glassW = maxMm("-x"), glassH = maxMm("-y"))
        }
    }
}

/** Cached [ScanCapabilities] per printer id. */
object ScanCapabilitiesStore {
    private const val PREFS = "ufr_scan_caps2"   // 2: + glass size (older entries lack it)
    fun get(ctx: Context, printerId: String): ScanCapabilities? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(printerId, null)
            ?.let { runCatching { ScanCapabilities.fromJson(org.json.JSONObject(it)) }.getOrNull() }
    fun set(ctx: Context, printerId: String, c: ScanCapabilities) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(printerId, c.toJson().toString()).apply()
}

/** Part of the glass to scan, in mm from the top-left corner (the alignment mark). */
data class ScanArea(val left: Float, val top: Float, val width: Float, val height: Float) {
    /** Kept inside a glass of [glassW] × [glassH] mm. */
    fun clamp(glassW: Float, glassH: Float): ScanArea {
        val l = left.coerceIn(0f, glassW - 1f); val t = top.coerceIn(0f, glassH - 1f)
        return ScanArea(l, t, width.coerceIn(1f, glassW - l), height.coerceIn(1f, glassH - t))
    }
}

/** Standard originals, portrait, in mm. */
enum class PaperFormat(val label: String, val w: Float, val h: Float) {
    A4("A4", 210f, 297f), A5("A5", 148f, 210f), A6("A6", 105f, 148f), B5("B5", 176f, 250f), LETTER("Letter", 215.9f, 279.4f)
}

/**
 * Default scan area (Settings) used when no preview was taken: the whole glass, a standard
 * format placed at the glass mark, or a custom rectangle.
 */
data class AreaPreset(val format: PaperFormat? = null, val custom: ScanArea? = null) {
    val isFull: Boolean get() = format == null && custom == null

    /** In mm on this scanner's glass; null = whole glass. */
    fun area(glassW: Float, glassH: Float): ScanArea? = when {
        custom != null -> custom.clamp(glassW, glassH)
        format != null -> ScanArea(0f, 0f, minOf(format.w, glassW), minOf(format.h, glassH))
        else -> null
    }

    fun encode(): String = when {
        custom != null -> "custom:${custom.left},${custom.top},${custom.width},${custom.height}"
        format != null -> "format:${format.name}"
        else -> "full"
    }

    companion object {
        fun decode(s: String?): AreaPreset = runCatching {
            when {
                s == null || s == "full" -> AreaPreset()
                s.startsWith("format:") -> AreaPreset(format = PaperFormat.valueOf(s.removePrefix("format:")))
                s.startsWith("custom:") -> s.removePrefix("custom:").split(',').map { it.toFloat() }
                    .let { (l, t, w, h) -> AreaPreset(custom = ScanArea(l, t, w, h)) }
                else -> AreaPreset()
            }
        }.getOrDefault(AreaPreset())
    }
}

data class ScanSettings(
    val source: ScanSource = ScanSource.GLASS,
    val mode: ScanMode = ScanMode.COLOR,
    val dpi: Int = 300,
    /** Glass only; null = the whole glass. */
    val area: ScanArea? = null
)

/** Why a scan failed, mapped from scanimage's stderr to something the user can act on. */
enum class ScanError { BUSY, NO_PAPER, JAM, TIMEOUT, UNREACHABLE, UNSUPPORTED, OTHER }

sealed class ScanResult {
    data class Ok(val pages: List<File>, val detail: String) : ScanResult()
    data class Failed(val error: ScanError, val message: String, val detail: String) : ScanResult()
}

/**
 * Network scanning through the bundled `scanimage` (sane-backends 1.4.0, pixma
 * backend with the MF8000 patch, Android NDK build — scripts/build-scanimage-android.sh), exec'd directly
 * from nativeLibraryDir. Protocol MFNP (UDP/TCP 8610), unicast only: broadcast
 * discovery wedges the scanner until power-cycle (docs/20), so auto_detection=no.
 */
class ScanJobs(private val context: Context) {

    companion object {
        private const val BAND_ROWS = 512
        private const val OUT_DIR = "scan-out"

        /**
         * Where the original lies on the glass in a preview, as fractions (left, top, right, bottom).
         * Everything is measured from the preview itself: the lid's brightness sets the "dark"
         * threshold, and [glassW] × [glassH] mm (reported by the scanner) give the real size.
         * An original lying at the glass mark (top-left) snaps to the standard format it fills
         * (A4, A5, A6, B5, also landscape), otherwise the frame hugs the content with ~4 mm margin.
         * Null when nothing stands out or it covers nearly the whole glass.
         */
        fun contentBounds(bmp: Bitmap, glassW: Float, glassH: Float): FloatArray? {
            val w = bmp.width; val h = bmp.height
            if (w < 16 || h < 16) return null
            // The outermost 1 % often shows the glass frame or lid shadow.
            val edgeX = w / 100 + 1; val edgeY = h / 100 + 1
            fun lum(p: Int) = (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
            val row = IntArray(w)

            // Lid brightness = 90th percentile (the lid is most of the glass, and the brightest).
            val hist = IntArray(256)
            for (y in edgeY until h - edgeY step 2) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                for (x in edgeX until w - edgeX step 2) hist[lum(row[x])]++
            }
            val total = hist.sum()
            var acc = 0; var bg = 255
            for (v in 0..255) { acc += hist[v]; if (acc >= total * 0.9) { bg = v; break } }
            val dark = (bg - 40).coerceIn(100, 235)

            var l = w; var t = h; var r = -1; var b = -1
            for (y in edgeY until h - edgeY) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                var n = 0; var first = -1; var last = -1
                for (x in edgeX until w - edgeX) {
                    if (lum(row[x]) < dark) { n++; if (first < 0) first = x; last = x }
                }
                if (n >= 2) {   // a single dark pixel is dust
                    if (y < t) t = y; b = y
                    if (first < l) l = first; if (last > r) r = last
                }
            }
            if (r < 0) return null

            // Content in mm from the glass mark.
            val mmX = glassW / w; val mmY = glassH / h
            val cl = l * mmX; val ct = t * mmY; val cr = (r + 1) * mmX; val cb = (b + 1) * mmY
            if (cl <= 20f && ct <= 20f) {
                val fits = PaperFormat.entries.filter { it != PaperFormat.LETTER }
                    .flatMap { listOf(it.w to it.h, it.h to it.w) }
                    .filter { (fw, fh) -> fw <= glassW + 1f && fh <= glassH + 1f && cr <= fw && cb <= fh }
                    .minByOrNull { (fw, fh) -> fw * fh }
                // Snap only when the content fills most of the format (a small receipt keeps its own frame).
                if (fits != null && cr >= fits.first * 0.65f && cb >= fits.second * 0.65f) {
                    return floatArrayOf(0f, 0f, (fits.first / glassW).coerceAtMost(1f), (fits.second / glassH).coerceAtMost(1f))
                        .takeIf { it[2] * it[3] < 0.97f }
                }
            }
            val m = 4f   // mm around the content
            val box = floatArrayOf(
                ((cl - m) / glassW).coerceAtLeast(0f), ((ct - m) / glassH).coerceAtLeast(0f),
                ((cr + m) / glassW).coerceAtMost(1f), ((cb + m) / glassH).coerceAtMost(1f))
            val area = (box[2] - box[0]) * (box[3] - box[1])
            return box.takeIf { area in 0.002f..0.9f }
        }

        /**
         * Deletes leftover scan pages (26–104 MB each at 600 dpi) and finished outputs older
         * than [outputMaxAgeMs]. Outputs are kept for a while: a share target may still be
         * reading them through the FileProvider. [keep] names an output still waiting to be printed.
         */
        fun sweep(context: Context, outputMaxAgeMs: Long, keep: String? = null) {
            File(context.cacheDir, "scan").listFiles()?.forEach { it.delete() }
            val cutoff = System.currentTimeMillis() - outputMaxAgeMs
            File(context.cacheDir, OUT_DIR).listFiles()
                ?.forEach { if (it.lastModified() < cutoff && it.name != keep) it.delete() }
        }
    }

    private val binary = File(context.applicationInfo.nativeLibraryDir, "libscanimage.so")
    private val outDir = File(context.cacheDir, "scan").apply { mkdirs() }
    /** Finished PDF/JPEG/PNG for saving, sharing or printing; swept by [sweep]. */
    private val resultDir = File(context.cacheDir, OUT_DIR).apply { mkdirs() }

    /** Removes pages from the previous scan. */
    fun clear() {
        outDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Quick low-resolution scan of the whole glass for choosing the area. Returns the page as a
     * bitmap (75 dpi colour ≈ 640 × 880 px), or the failure.
     */
    suspend fun preview(ip: String, device: String?, mode: ScanMode, dpi: Int): Pair<Bitmap?, ScanResult> = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "scan-preview.pnm")
        val r = scan(ip, device, ScanSettings(ScanSource.GLASS, mode, dpi), previewTo = file) { _, _ -> }
        val bmp = if (r is ScanResult.Ok) runCatching { decode(file) }.getOrNull() else null
        file.delete()
        bmp to r
    }

    /**
     * Scans one page (glass) or the whole feeder stack. [onProgress] gets (page, percent).
     * Pages are PNM files in cacheDir/scan; a preview goes to [previewTo] and leaves them alone.
     */
    suspend fun scan(ip: String, device: String?, s: ScanSettings, previewTo: File? = null,
                     onProgress: (Int, Int) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        if (!binary.exists()) return@withContext ScanResult.Failed(ScanError.OTHER, "libscanimage.so chybí", "")
        if (previewTo == null) clear()
        val cfg = config(ip)
        val dev = deviceFor(ip, device)
            ?: return@withContext ScanResult.Failed(ScanError.UNSUPPORTED, "scanimage -L: žádný skener na $ip", "")

        val cmd = mutableListOf(
            binary.absolutePath, "-d", dev,
            // source first: the backend resets mode/resolution when the source changes
            "--source", s.source.sane, "--mode", s.mode.sane, "--resolution", "${s.dpi}",
            "--format=pnm", "-p"
        )
        if (s.source == ScanSource.FEEDER) {
            cmd += listOf("--batch=${File(outDir, "page%03d.pnm").absolutePath}", "--batch-start=1")
        }
        s.area?.takeIf { s.source == ScanSource.GLASS }?.let { a ->
            fun mm(v: Float) = String.format(java.util.Locale.US, "%.1f", v)
            cmd += listOf("-l", mm(a.left), "-t", mm(a.top), "-x", mm(a.width), "-y", mm(a.height))
        }
        AppLog.acc("${if (previewTo != null) "Náhled" else "Sken"} ${s.source} ${s.mode} ${s.dpi} dpi" +
            (s.area?.let { " oblast ${it.width.toInt()}×${it.height.toInt()} mm" } ?: "") + " → $ip")
        Log.i(TAG, "[scan] ${cmd.joinToString(" ")}")

        val single = previewTo ?: File(outDir, "page001.pnm")
        val pb = ProcessBuilder(cmd).apply {
            environment()["SANE_CONFIG_DIR"] = cfg.absolutePath
            environment()["HOME"] = context.cacheDir.absolutePath
            environment()["TMPDIR"] = context.cacheDir.absolutePath
            // No resolv.conf on Android: make the reverse lookup in the backend give up fast.
            environment()["RES_OPTIONS"] = "timeout:1 attempts:1"
            if (s.source == ScanSource.GLASS) redirectOutput(single)
        }
        val t0 = System.currentTimeMillis()
        val proc = pb.start()
        val log = StringBuilder()
        var page = 1
        val reader = Thread {
            proc.errorStream.bufferedReader().forEachLine { line ->
                synchronized(log) { log.appendLine(line) }
                // "Progress: 42.3%" (with -p) and "Scanning page 2" (batch mode)
                Regex("""Progress:\s*([\d.]+)%""").find(line)?.let {
                    onProgress(page, it.groupValues[1].toFloat().toInt())
                }
                Regex("""Scanning page (\d+)""").find(line)?.let {
                    page = it.groupValues[1].toInt(); onProgress(page, 0)
                }
            }
        }.apply { start() }

        // The patched backend has its own 30 s no-data watchdog (jam → EPAPER_JAMMED);
        // this outer limit only catches a hang outside the transfer.
        val limitMin = if (s.source == ScanSource.FEEDER) 15L else 4L
        val finished = proc.waitFor(limitMin, TimeUnit.MINUTES)
        if (!finished) proc.destroy()
        reader.join(2000)
        val exit = if (finished) proc.exitValue() else -1
        val detail = synchronized(log) { log.toString() }
        AppLog.dim("scanimage exit=$exit za ${(System.currentTimeMillis() - t0) / 1000} s\n$detail")

        val pages = if (previewTo != null) listOf(previewTo).filter { it.length() > 0 }
            else outDir.listFiles { f -> f.name.endsWith(".pnm") && f.length() > 0 }?.sortedBy { it.name } ?: emptyList()
        val err = classify(detail, exit, finished)
        when {
            // The feeder always ends with "out of documents" after the last page.
            pages.isNotEmpty() && (exit == 0 || err == ScanError.NO_PAPER) -> {
                AppLog.ok(if (previewTo != null) "Náhled hotový" else "Naskenováno stran: ${pages.size}")
                ScanResult.Ok(pages, detail)
            }
            else -> {
                pages.forEach { it.delete() }
                val e = err ?: ScanError.OTHER
                AppLog.err("Sken selhal: $e")
                ScanResult.Failed(e, detail.lines().lastOrNull { it.isNotBlank() } ?: "exit=$exit", detail)
            }
        }
    }

    /** SANE config dir: only the pixma backend, unicast to [ip] (broadcast wedges the scanner). */
    private fun config(ip: String): File {
        val cfg = File(context.filesDir, "sane").apply { mkdirs() }
        File(cfg, "dll.conf").writeText("pixma\n")
        File(cfg, "pixma.conf").writeText("auto_detection=no\nmfnp://${mfnpHost(ip)}\n")
        return cfg
    }

    /**
     * Host part of the mfnp:// URI. IPv6 goes in brackets; a link-local zone is given as the
     * interface index ("%24"), which the static scanimage resolves without if_nametoindex.
     */
    private fun mfnpHost(ip: String): String {
        if (!PrinterAddress.isIpv6(ip)) return ip
        val addr = ip.substringBefore('%')
        val zone = ip.substringAfter('%', "").takeIf { it.isNotEmpty() }
            ?.let { z -> z.toIntOrNull() ?: runCatching { java.net.NetworkInterface.getByName(z)?.index }.getOrNull() }
        return if (zone != null) "[$addr%$zone]" else "[$addr]"
    }

    /**
     * The SANE device name. Over IPv4 it contains the address (pixma:MF8000_192.168.0.62), so a
     * cached name is reused only while the address matches; over IPv6 it is MAC-based and stable.
     * Otherwise `scanimage -L` (unicast to the configured host, never broadcast) finds it.
     */
    private fun deviceFor(ip: String, known: String?): String? {
        if (known != null && (known.endsWith("_$ip") || !Regex("_[0-9.]+$").containsMatchIn(known))) return known
        return listDevice(ip)
    }

    /** `scanimage -L` → first "device `pixma:…'" name; null when the scanner does not answer or its model is unknown. */
    fun listDevice(ip: String): String? {
        if (!binary.exists()) return null
        val pb = ProcessBuilder(binary.absolutePath, "-L").apply {
            environment()["SANE_CONFIG_DIR"] = config(ip).absolutePath
            environment()["HOME"] = context.cacheDir.absolutePath
            environment()["TMPDIR"] = context.cacheDir.absolutePath
            environment()["RES_OPTIONS"] = "timeout:1 attempts:1"
            redirectErrorStream(true)
        }
        val proc = pb.start()
        val text = StringBuilder()
        val reader = Thread { proc.inputStream.bufferedReader().forEachLine { text.appendLine(it) } }.apply { start() }
        if (!proc.waitFor(30, TimeUnit.SECONDS)) proc.destroy()
        reader.join(2000)
        val name = Regex("device [`'](pixma:[^']+)'").find(text)?.groupValues?.get(1)
        if (name != null) AppLog.ok("Skener: $name") else AppLog.err("scanimage -L nenašel skener na $ip\n$text")
        return name
    }

    /**
     * Asks the scanner what it offers (`scanimage -A`: lists the options, scans nothing).
     * Returns null when the scanner does not answer. The resolution list is read with the
     * default source (Flatbed); the pixma backend uses the same list for the feeder on this model.
     */
    suspend fun queryOptions(ip: String): ScanCapabilities? = withContext(Dispatchers.IO) {
        if (!binary.exists()) return@withContext null
        val dev = listDevice(ip) ?: return@withContext null
        val pb = ProcessBuilder(binary.absolutePath, "-d", dev, "-A").apply {
            environment()["SANE_CONFIG_DIR"] = config(ip).absolutePath
            environment()["HOME"] = context.cacheDir.absolutePath
            environment()["TMPDIR"] = context.cacheDir.absolutePath
            environment()["RES_OPTIONS"] = "timeout:1 attempts:1"
            redirectErrorStream(true)
        }
        val proc = pb.start()
        val out = StringBuilder()
        val reader = Thread { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { start() }
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) proc.destroy()
        reader.join(2000)
        val text = out.toString()
        AppLog.dim("scanimage -A exit=${if (finished) proc.exitValue() else "timeout"}\n$text")
        ScanCapabilities.parse(text)?.copy(device = dev)?.also {
            AppLog.ok("Skener nabízí: ${it.sources.joinToString()} · ${it.modes.joinToString()} · ${it.resolutions.joinToString()} dpi")
        }
    }

    private fun classify(stderr: String, exit: Int, finished: Boolean): ScanError? {
        val s = stderr.lowercase()
        return when {
            !finished -> ScanError.TIMEOUT
            // 128 + SIGSYS: Android's seccomp killed scanimage (a syscall apps may not use).
            exit == 159 -> ScanError.OTHER.also { AppLog.err("scanimage zabit systémem (SIGSYS, seccomp)") }
            "out of documents" in s || "no more documents" in s || "no paper" in s -> ScanError.NO_PAPER
            "jammed" in s -> ScanError.JAM
            "busy" in s -> ScanError.BUSY
            "invalid argument" in s && "open of device" in s -> ScanError.UNREACHABLE
            "error during device i/o" in s || "timed out" in s -> ScanError.TIMEOUT
            exit != 0 -> ScanError.OTHER
            else -> null
        }
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    /** Decodes a scanned page, downsampled by [sample] (1 = full size). */
    fun decode(page: File, sample: Int = 1): Bitmap? = runCatching {
        BufferedInputStream(page.inputStream(), 1 shl 16).use { Pnm.decode(it, sample) }
    }.onFailure { Log.e(TAG, "[scan] decode ${page.name}", it) }.getOrNull()

    /**
     * All pages as one PDF in cacheDir/scan-out (for saving, sharing or printing a copy). Pages are
     * decoded and drawn in horizontal bands, so even 600 dpi colour never needs a full-page bitmap.
     */
    suspend fun toPdf(pages: List<File>, dpi: Int, name: String): File = withContext(Dispatchers.IO) {
        val out = File(resultDir, name)
        val doc = PdfDocument()
        try {
            pages.forEachIndexed { i, f ->
                val (w, h) = BufferedInputStream(f.inputStream()).use { Pnm.size(it) }
                // PDF points = 1/72 in; the page keeps the scanned size.
                val k = 72f / dpi
                val pg = doc.startPage(PdfDocument.PageInfo.Builder((w * k).toInt().coerceAtLeast(1),
                    (h * k).toInt().coerceAtLeast(1), i + 1).create())
                pg.canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                BufferedInputStream(f.inputStream(), 1 shl 16).use { input ->
                    Pnm.decodeBands(input, BAND_ROWS) { band, y0 ->
                        pg.canvas.drawBitmap(band, null,
                            android.graphics.RectF(0f, y0 * k, w * k, (y0 + band.height) * k), paint)
                    }
                }
                doc.finishPage(pg)
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        out
    }

    /** Saves [file] to Downloads/UFR-II (MediaStore, no storage permission on API 29+). */
    suspend fun saveToDownloads(file: File, mime: String): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/UFR-II")
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        cr.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        uri
    }

    /** Copies [file] to a document the user created with the system picker (any folder / cloud). */
    suspend fun saveTo(file: File, target: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
        }.getOrDefault(false)
    }

    /** One page as JPEG or PNG. Large pages (600 dpi) decode as RGB_565 to halve the memory. */
    suspend fun toImage(page: File, name: String, format: ScanFormat): File = withContext(Dispatchers.IO) {
        val out = File(resultDir, name)
        val (w, h) = BufferedInputStream(page.inputStream()).use { Pnm.size(it) }
        val config = if (w.toLong() * h > 20_000_000L) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        val bmp = BufferedInputStream(page.inputStream(), 1 shl 16).use { Pnm.decode(it, 1, config) }
        val cf = if (format == ScanFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        out.outputStream().use { bmp.compress(cf, 90, it) }
        bmp.recycle()
        out
    }
}

/** Minimal binary PNM reader (P4 bitmap, P5 gray, P6 RGB, maxval ≤ 255) — scanimage --format=pnm. */
object Pnm {
    private class Header(val magic: String, val w: Int, val h: Int, val max: Int) {
        val bpp = when (magic) { "P6" -> 3; "P5" -> 1; "P4" -> 0; else -> error("PNM $magic nepodporováno") }
        val rowBytes = if (bpp == 0) (w + 7) / 8 else w * bpp
    }

    private fun header(input: InputStream): Header {
        val magic = token(input)
        val w = token(input).toInt()
        val h = token(input).toInt()
        val max = if (magic == "P4") 1 else token(input).toInt()
        require(max in 1..255) { "PNM maxval $max nepodporováno" }
        return Header(magic, w, h, max)
    }

    /** Width × height from the header only. */
    fun size(input: InputStream): Pair<Int, Int> = header(input).let { it.w to it.h }

    fun decode(input: InputStream, sample: Int = 1, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val hd = header(input)
        val step = sample.coerceAtLeast(1)
        val ow = (hd.w + step - 1) / step
        val oh = (hd.h + step - 1) / step
        val bmp = Bitmap.createBitmap(ow, oh, config)
        val row = ByteArray(hd.rowBytes)
        val outRow = IntArray(ow)
        var oy = 0
        for (y in 0 until hd.h) {
            readFully(input, row)
            if (y % step != 0) continue
            convert(hd, row, outRow, step)
            bmp.setPixels(outRow, 0, ow, 0, oy++, ow, 1)
        }
        return bmp
    }

    /** Full-resolution decode in bands of [rows]; [onBand] gets (band bitmap, first row). */
    fun decodeBands(input: InputStream, rows: Int, onBand: (Bitmap, Int) -> Unit) {
        val hd = header(input)
        val band = Bitmap.createBitmap(hd.w, rows, Bitmap.Config.ARGB_8888)
        val row = ByteArray(hd.rowBytes)
        val outRow = IntArray(hd.w)
        try {
            var y0 = 0
            while (y0 < hd.h) {
                val n = minOf(rows, hd.h - y0)
                for (r in 0 until n) {
                    readFully(input, row)
                    convert(hd, row, outRow, 1)
                    band.setPixels(outRow, 0, hd.w, 0, r, hd.w, 1)
                }
                onBand(if (n == rows) band else Bitmap.createBitmap(band, 0, 0, hd.w, n), y0)
                y0 += n
            }
        } finally {
            band.recycle()
        }
    }

    private fun convert(hd: Header, row: ByteArray, out: IntArray, step: Int) {
        for (ox in out.indices) {
            val x = ox * step
            if (x >= hd.w) break
            out[ox] = when (hd.bpp) {
                3 -> Color.rgb(scale(row[x * 3], hd.max), scale(row[x * 3 + 1], hd.max), scale(row[x * 3 + 2], hd.max))
                1 -> { val v = scale(row[x], hd.max); Color.rgb(v, v, v) }
                // P4: 1 = black
                else -> if ((row[x shr 3].toInt() shr (7 - (x and 7))) and 1 == 1) Color.BLACK else Color.WHITE
            }
        }
    }

    private fun scale(b: Byte, max: Int): Int = (b.toInt() and 0xFF).let { if (max == 255) it else it * 255 / max }

    private fun token(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            require(c >= 0) { "PNM: neočekávaný konec hlavičky" }
            when {
                c == '#'.code -> while (true) { val d = input.read(); if (d < 0 || d == '\n'.code) break }
                Character.isWhitespace(c) -> if (sb.isNotEmpty()) return sb.toString()
                else -> sb.append(c.toChar())
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            require(n > 0) { "PNM: data jsou kratší než hlavička" }
            off += n
        }
    }
}
