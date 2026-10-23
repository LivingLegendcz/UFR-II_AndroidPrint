package cz.ufrii.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "CanonPrint"

/** Outcome of one in-app print job (share / file picker / test page). */
data class JobResult(
    val ok: Boolean,
    /** Short human-readable reason on failure (ConnectException, exit code …). */
    val message: String,
    /** Full ProotLauncher summary for the log. */
    val detail: String,
    val elapsedMs: Long
)

/**
 * In-app print jobs, moved out of the old MainActivity so the Compose UI can call them.
 * Jobs are serialised by [lock]: the PrintService has its own single-thread executor,
 * but the in-app path must not run two proot filters sharing one temp file either.
 */
class PrintJobs(private val context: Context) {

    companion object {
        private val lock = Mutex()

        /** The in-app job that is printing now ("Zrušit" on Home / Print / printer detail). */
        @Volatile private var active: CancelToken? = null

        /** Stops the running in-app job; false when nothing is printing. */
        fun cancelActive(): Boolean {
            val t = active ?: return false
            AppLog.err("Tisk zrušen uživatelem")
            t.cancel()
            // A manual-duplex job may be waiting for the stack to be turned over.
            DuplexGate.prompt.value?.let { DuplexGate.resolve(it.id, false) }
            return true
        }

        private fun enterJob() = CancelToken().also { active = it; CancelToken.enter(it) }
        private fun exitJob() { CancelToken.exit(); active = null }
    }

    /**
     * Prints a user file (PDF or image) from [uri] with [opts]. [onProgress] gets
     * (pass label or null, page index, page total) while pages are rendered.
     */
    suspend fun printFile(
        uri: Uri,
        isImage: Boolean,
        printer: Printer,
        opts: PrintOptions,
        title: String,
        onProgress: (String?, Int, Int) -> Unit = { _, _, _ -> }
    ): JobResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val printer = PrinterAddress.resolve(context, printer)
            enterJob()
            val t0 = System.currentTimeMillis()
            val tmpFile = File(context.cacheDir, "print_input.tmp")
            AppLog.acc("Tisk ${if (isImage) "obrázku" else "PDF"} → ${printer.name} ${printer.ip}:${printer.port} " +
                "(${opts.colorMode}, ${opts.source.ppd}, kopie ${opts.copies}${if (opts.manualDuplex) ", oboustranně" else ""})")
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@withContext fail(t0, "Nelze otevřít soubor", "openInputStream($uri) == null")
                input.use { inp -> tmpFile.outputStream().use { out -> inp.copyTo(out) } }
                AppLog.i("Vstup zkopírován (${tmpFile.length()} B)")

                val launcher = ProotLauncher(context)
                val ppd = PpdCatalog.forPrinter(context, printer)
                val detail = when {
                    isImage -> {
                        val bmp = decodeImage(tmpFile)
                            ?: return@withContext fail(t0, "Obrázek nelze načíst", "BitmapFactory vrátil null")
                        try {
                            onProgress(null, 0, 1)
                            StallRetry.run(context, title, printer.ip) {
                                launcher.printImage(bmp, opts.colorMode, printer.ip, printer.port, opts.source, ppd)
                            }
                        } finally {
                            bmp.recycle()
                        }
                    }
                    opts.manualDuplex && !PpdCatalog.autoDuplex(context, printer) -> {
                        var pass: String? = null
                        ManualDuplexJob.run(context, tmpFile, printer, opts, title,
                            onStatus = { pass = it },
                            onPage = { i, n -> onProgress(pass, i, n) })
                    }
                    else -> StallRetry.run(context, title, printer.ip) {
                        ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                            launcher.printPdf(pfd, opts.colorMode, printer.ip, printer.port, opts.copies, opts.source,
                                opts.pages, false, { i, n -> onProgress(null, i, n) }, ppd, duplex = opts.manualDuplex)
                        }
                    }
                }
                finish(t0, detail, title, printer, opts.manualDuplex)
            } catch (e: Throwable) {
                // Throwable: OutOfMemoryError on huge photos must not crash the app.
                Log.e(TAG, "[job] failed", e)
                fail(t0, "${e.javaClass.simpleName}: ${e.message}", e.stackTraceToString(), title, printer)
            } finally {
                // S2: don't leave the shared document in cache.
                runCatching { tmpFile.delete() }
                exitJob()
            }
        }
    }

    /** Number of pages of a PDF [uri] (null when it cannot be opened seekably). */
    suspend fun pdfPageCount(uri: Uri): Int? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { it.pageCount }
            }
        }.getOrNull()
    }

    /**
     * Duplex calibration, one sheet: [step] 1 prints side "1" on a blank sheet, step 2
     * prints side "2" after the user re-inserted it as instructed.
     */
    suspend fun printCalibration(printer: Printer, source: PaperSource, step: Int, rotate180: Boolean = false): JobResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val printer = PrinterAddress.resolve(context, printer)
            enterJob()
            val t0 = System.currentTimeMillis()
            AppLog.acc("Kalibrace oboustranného tisku, krok $step → ${printer.name} (${source.ppd})")
            try {
                val detail = StallRetry.run(context, "Kalibrace", printer.ip) {
                    ProotLauncher(context).printDrawn(
                        listOf(ManualDuplexJob.calibrationPage(step, rotate180,
                            context.getString(R.string.calib_page_label, step))), printer.ip, printer.port, source,
                        PpdCatalog.forPrinter(context, printer))
                }
                finish(t0, detail, null, printer)
            } catch (e: Throwable) {
                Log.e(TAG, "[job] calibration failed", e)
                fail(t0, "${e.javaClass.simpleName}: ${e.message}", e.stackTraceToString())
            } finally {
                exitJob()
            }
        }
    }

    /** Prints the bundled assets/sample.pdf test page. */
    suspend fun printTestPage(colorMode: ColorMode, printer: Printer): JobResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val printer = PrinterAddress.resolve(context, printer)
            enterJob()
            val t0 = System.currentTimeMillis()
            val tmpFile = File(context.cacheDir, "sample_test.tmp")
            AppLog.acc("Testovací stránka → ${printer.name} ${printer.ip}:${printer.port} ($colorMode)")
            try {
                context.assets.open("sample.pdf").use { inp ->
                    tmpFile.outputStream().use { out -> inp.copyTo(out) }
                }
                val detail = StallRetry.run(context, "sample.pdf", printer.ip) {
                    ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        ProotLauncher(context).printPdf(pfd, colorMode, printer.ip, printer.port, 1, AppSettings.defaultSource.value,
                            ppd = PpdCatalog.forPrinter(context, printer))
                    }
                }
                finish(t0, detail, null, printer)
            } catch (e: Throwable) {
                Log.e(TAG, "[job] test page failed", e)
                fail(t0, "${e.javaClass.simpleName}: ${e.message}", e.stackTraceToString())
            } finally {
                runCatching { tmpFile.delete() }
                exitJob()
            }
        }
    }

    /**
     * Runs the proot/filter diagnostics (old DIAGNOSTIKA button), logging each step as it
     * finishes. [onStep] gets (index, total, label) before each step for a progress UI.
     */
    suspend fun diagnostics(onStep: (Int, Int, String) -> Unit = { _, _, _ -> }): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            val launcher = ProotLauncher(context)
            // The test binaries live in the debug source set only; a release build skips their steps.
            val devProbes = File(context.applicationInfo.nativeLibraryDir, "libtest_hello.so").exists()
            val steps: List<Pair<String, () -> String>> = listOfNotNull(
                "Zařízení a knihovny" to launcher::diagnostics,
                ("Přímý exec" to launcher::runBinaryDirect).takeIf { devProbes },
                ("ptrace sonda (vývojová)" to launcher::runPtraceProbe).takeIf { devProbes },
                ("proot" to launcher::runProotTest).takeIf { devProbes },
                "cnjbigufr2 (až 8 s)" to launcher::runCnjbigProbe
            )
            steps.forEachIndexed { i, (label, run) ->
                onStep(i, steps.size, label)
                AppLog.acc("--- $label ---")
                AppLog.i(runCatching(run).getOrElse { "${it.javaClass.simpleName}: ${it.message}" })
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Same success rule as CanonPrintService: filter exit 0 AND data delivered to the socket. */
    private fun finish(t0: Long, detail: String, title: String?, printer: Printer, duplex: Boolean = false): JobResult {
        // Manual duplex: every pass must succeed (each one logs its own exitCode / socket line).
        val ok = ManualDuplexJob.allPassesOk(detail)
        val msg = if (ok) "OK" else shortReason(detail)
        if (ok) AppLog.ok("Vytištěno") else AppLog.err("Tisk selhal: $msg")
        AppLog.dim(detail)
        if (title != null) {
            JobHistory.add(context, HistoryEntry(System.currentTimeMillis(), title, printer.name, ok,
                if (ok) "" else msg, HistoryEntry.VIA_APP, duplex))
        }
        return JobResult(ok, msg, detail, System.currentTimeMillis() - t0)
    }

    private fun fail(t0: Long, msg: String, detail: String, title: String? = null, printer: Printer? = null): JobResult {
        AppLog.err("Tisk selhal: $msg")
        AppLog.dim(detail)
        if (title != null && printer != null) {
            JobHistory.add(context, HistoryEntry(System.currentTimeMillis(), title, printer.name, false, msg,
                HistoryEntry.VIA_APP))
        }
        return JobResult(false, msg, detail, System.currentTimeMillis() - t0)
    }

    /** First meaningful failure line of a ProotLauncher summary. */
    private fun shortReason(detail: String): String =
        detail.lines().firstOrNull {
            it.contains("Exception") || it.contains("FAIL") || it.contains("CHYBA") ||
                (it.contains("exitCode=") && !it.contains("exitCode=0"))
        }?.trim()?.take(160) ?: "Neznámá chyba"

    private fun decodeImage(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = inSampleSize(bounds.outWidth, bounds.outHeight,
            CupsRasterWriter.CUPS_WIDTH, CupsRasterWriter.CUPS_HEIGHT)
        AppLog.i("Obrázek ${bounds.outWidth}×${bounds.outHeight}, inSampleSize=$sample")
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        return applyExifRotation(bmp, file)
    }

    /** Largest power-of-two subsample that still keeps the image ≥ the raster canvas. */
    private fun inSampleSize(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Int {
        var s = 1
        while (srcW / (s * 2) >= maxW && srcH / (s * 2) >= maxH) s *= 2
        return s
    }

    /**
     * F3: Reads EXIF orientation from [file] and returns a re-oriented bitmap.
     * Returns [bmp] unchanged when no rotation is needed or on error; when a rotated
     * copy is produced, the original [bmp] is recycled.
     */
    private fun applyExifRotation(bmp: Bitmap, file: File): Bitmap {
        return try {
            val orientation = ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
                else -> return bmp  // NORMAL / UNDEFINED — nothing to do
            }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated != bmp) bmp.recycle()
            rotated
        } catch (e: Exception) {
            Log.w(TAG, "[job] EXIF rotation failed (using original): ${e.message}")
            bmp
        }
    }
}
