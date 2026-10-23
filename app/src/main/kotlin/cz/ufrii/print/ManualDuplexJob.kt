package cz.ufrii.print

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Manual double-sided printing for printers without a duplex unit (MF8030Cn):
 * pass 1 prints the fronts, [DuplexGate] waits until the user re-inserts the stack,
 * pass 2 prints the backs (order and rotation from the tray's [DuplexCalibration]).
 * Blocking — call from a worker thread / Dispatchers.IO.
 */
object ManualDuplexJob {

    /**
     * Defaults derived from the MF8030Cn paper path until the user calibrates:
     * cassette = C-path (prints the side facing down → re-insert printed side UP);
     * manual feed slot = prints the side facing up → re-insert printed side DOWN.
     * Top edge first in both cases, so no rotation.
     */
    fun defaultCalibration(source: PaperSource) = when (source) {
        PaperSource.MANUAL -> DuplexCalibration(faceUp = false, rotate180 = false)
        else -> DuplexCalibration(faceUp = true, rotate180 = false)
    }

    /** Auto lets the printer choose the tray — useless for re-inserting, so duplex uses the cassette. */
    fun duplexSource(source: PaperSource) = if (source == PaperSource.AUTO) PaperSource.CASSETTE else source

    fun calibrationFor(ctx: Context, printerId: String, source: PaperSource): Pair<DuplexCalibration, Boolean> {
        val saved = DuplexCalibrations.get(ctx, printerId, source)
        return (saved ?: defaultCalibration(source)) to (saved != null)
    }

    fun pageCount(file: File): Int =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }

    /**
     * Runs both passes for [file]. [onStatus] gets short progress texts. Returns the
     * combined ProotLauncher summary (success rule: every pass exitCode=0 + socket OK).
     */
    fun run(
        ctx: Context,
        file: File,
        printer: Printer,
        opts: PrintOptions,
        title: String,
        onStatus: (String) -> Unit = {},
        onPage: ((Int, Int) -> Unit)? = null
    ): String {
        val source = duplexSource(opts.source)
        val (cal, calibrated) = calibrationFor(ctx, printer.id, source)
        val pages = opts.pages ?: (0 until pageCount(file)).toList()
        val plan = DuplexPlanner.plan(pages, opts.copies, source, cal)
        val launcher = ProotLauncher(ctx)
        val ppd = PpdCatalog.forPrinter(ctx, printer)
        AppLog.acc("Oboustranně ručně: ${plan.sheets} listů, zásobník ${source.ppd}, " +
            "lícem ${if (cal.faceUp) "nahoru" else "dolů"}${if (calibrated) "" else " (výchozí)"}")

        onStatus("1/2")
        val first = StallRetry.run(ctx, title, printer.ip) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                launcher.printPdf(pfd, opts.colorMode, printer.ip, printer.port, 1, source, plan.fronts, false, onPage, ppd)
            }
        }
        if (!passOk(first)) return first

        val prompt = DuplexPrompt(System.currentTimeMillis(), title, plan.sheets, source, cal.faceUp)
        Notifications.duplexPrompt(ctx, prompt, calibrated)
        val proceed = try { DuplexGate.awaitBlocking(prompt) } finally { Notifications.cancelDuplex(ctx) }
        if (!proceed) return first + "\nDUPLEX: druhá strana zrušena uživatelem — FAIL: zrušeno"

        onStatus("2/2")
        val second = StallRetry.run(ctx, title, printer.ip) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                launcher.printPdf(pfd, opts.colorMode, printer.ip, printer.port, 1, source, plan.backs, cal.rotate180, onPage, ppd)
            }
        }
        return first + "\n" + second
    }

    fun passOk(detail: String) = detail.contains("exitCode=0") && detail.contains("socket OK:")

    /** Success of a whole job summary: every "=== TISK" pass OK and not cancelled between passes. */
    fun allPassesOk(detail: String): Boolean {
        val passes = detail.split("=== TISK").drop(1)
        return passes.isNotEmpty() && passes.all(::passOk) && !detail.contains("FAIL: zrušeno")
    }

    /** Calibration sheet sides: big number, arrow at the top edge, label text. */
    /**
     * Calibration side [number]: arrow at the top edge, the number at a height of its own
     * (1 high, 2 middle, 3 low …) so numbers printed onto the same side stay readable.
     * [rotate180] prints it upside down, as the back sides of the tested setting would be.
     */
    fun calibrationPage(number: Int, rotate180: Boolean = false, label: String = "$number"): (Canvas) -> Unit = { c ->
        val w = CupsRasterWriter.CUPS_WIDTH.toFloat()
        val h = CupsRasterWriter.CUPS_HEIGHT.toFloat()
        if (rotate180) c.rotate(180f, w / 2, h / 2)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
        // Arrow pointing at the top edge.
        val arrow = Path().apply {
            moveTo(w / 2, 300f); lineTo(w / 2 + 600f, 1100f); lineTo(w / 2 + 220f, 1100f)
            lineTo(w / 2 + 220f, 1900f); lineTo(w / 2 - 220f, 1900f); lineTo(w / 2 - 220f, 1100f)
            lineTo(w / 2 - 600f, 1100f); close()
        }
        c.drawPath(arrow, ink)
        val text = Paint(ink).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        // Below the arrow (it ends at ~28 % of the height): 1, 2, 3 each at its own height.
        val slot = (number - 1) % 3
        val y = h * (0.47f + 0.17f * slot)
        text.textSize = 1100f
        c.drawText("$number", w / 2, y, text)
        text.textSize = 150f
        c.drawText(label, w / 2, y + 240f, text)
    }
}
