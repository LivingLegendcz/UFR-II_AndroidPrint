package cz.ufrii.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import cz.ufrii.print.status.PrinterCapabilities
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * One printer's setup as a link for a QR code, so another phone needs no discovery, capability
 * test or duplex calibration prints: addresses, model, capabilities, duplex calibration per tray,
 * scanner options and the defaults (paper source, scan area). JSON → deflate → base64url.
 */
object SettingsShare {

    const val SCHEME = "ufrprint"
    private const val PREFIX = "$SCHEME://import?d="
    private const val VERSION = 1

    /** What a shared link carries; [printer] has a fresh id until [apply] matches an existing one. */
    data class Import(
        val printer: Printer,
        val duplex: Map<PaperSource, DuplexCalibration>,
        val scanCaps: ScanCapabilities?,
        val source: PaperSource?,
        val scanArea: AreaPreset?
    )

    fun export(ctx: Context, p: Printer): String {
        val printer = JSONObject()
            .put("name", p.name).put("ip", p.ip).put("port", p.port).put("model", p.model).put("color", p.colorCapable)
        p.ipv6?.let { printer.put("ipv6", PrinterAddress.stripZone(it)) }
        p.capabilities?.let { printer.put("caps", it.toJson()) }
        p.ppd?.let { printer.put("ppd", it).put("ppdManual", p.ppdManual) }
        val duplex = JSONObject()
        for (s in PaperSource.entries) {
            DuplexCalibrations.get(ctx, p.id, s)?.let {
                duplex.put(s.name, JSONObject().put("faceUp", it.faceUp).put("rotate180", it.rotate180))
            }
        }
        val o = JSONObject().put("v", VERSION).put("printer", printer).put("duplex", duplex)
            .put("source", AppSettings.defaultSource.value.ppd)
            .put("scanArea", AppSettings.scanArea.value.encode())
        ScanCapabilitiesStore.get(ctx, p.id)?.let { o.put("scan", it.toJson()) }
        return PREFIX + Base64.encodeToString(deflate(o.toString().toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Accepts the whole link (QR, pasted text) or just its data part; null when it is not ours. */
    fun parse(text: String): Import? = runCatching {
        val data = text.trim().substringAfter("d=", text.trim())
        val o = JSONObject(String(inflate(Base64.decode(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))))
        if (o.optInt("v") > VERSION) return null
        val pj = o.getJSONObject("printer")
        val caps = pj.optJSONObject("caps")?.let { PrinterCapabilities.fromJson(it) }
        val printer = Printer(
            id = PrinterStore.newManualId(),
            name = pj.getString("name"),
            ip = pj.getString("ip"),
            port = pj.optInt("port", PrinterStore.DEFAULT_PORT),
            colorCapable = pj.optBoolean("color", true),
            source = PrinterSource.MANUAL,
            model = pj.optString("model", "Canon"),
            capabilities = caps,
            ipv6 = pj.optString("ipv6").takeIf { it.isNotBlank() },
            ppd = pj.optString("ppd").takeIf { it.isNotBlank() },
            ppdManual = pj.optBoolean("ppdManual")
        )
        val dj = o.optJSONObject("duplex")
        val duplex = PaperSource.entries.mapNotNull { s ->
            dj?.optJSONObject(s.name)?.let { s to DuplexCalibration(it.getBoolean("faceUp"), it.getBoolean("rotate180")) }
        }.toMap()
        Import(
            printer, duplex,
            o.optJSONObject("scan")?.let { ScanCapabilities.fromJson(it) },
            o.optString("source").takeIf { it.isNotBlank() }?.let { PaperSource.fromPpd(it) },
            o.optString("scanArea").takeIf { it.isNotBlank() }?.let { AreaPreset.decode(it) }
        )
    }.getOrNull()

    /**
     * Saves the printer (updating one with the same address instead of adding a duplicate) and its
     * calibration; [defaults] also takes over the paper source and scan area. Returns the saved id.
     */
    fun apply(ctx: Context, imp: Import, defaults: Boolean): String {
        val existing = PrinterStore.loadManual(ctx).firstOrNull {
            it.ip == imp.printer.ip || (imp.printer.ipv6 != null && it.ipv6 == imp.printer.ipv6)
        }
        val p = imp.printer.copy(id = existing?.id ?: imp.printer.id)
        PrinterStore.update(ctx, p)
        if (PrinterStore.loadManual(ctx).size == 1) PrinterStore.setDefault(ctx, p.id)
        imp.duplex.forEach { (s, c) -> DuplexCalibrations.set(ctx, p.id, s, c) }
        imp.scanCaps?.let { ScanCapabilitiesStore.set(ctx, p.id, it) }
        if (defaults) {
            imp.source?.let { AppSettings.setDefaultSource(ctx, it) }
            imp.scanArea?.let { AppSettings.setScanArea(ctx, it) }
        }
        PrinterAddress.invalidate(p.id)
        AppLog.ok("Nastavení načteno: ${p.name} ${p.ip}, kalibrace ${imp.duplex.size}×")
        return p.id
    }

    /** QR code bitmap of [text], [size] px square, black on white. */
    fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(b); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(b: ByteArray): ByteArray {
        val i = Inflater().apply { setInput(b) }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0 && (i.needsInput() || i.needsDictionary())) break
            out.write(buf, 0, n)
            require(out.size() < 64 * 1024) { "too large" }
        }
        i.end()
        return out.toByteArray()
    }
}
