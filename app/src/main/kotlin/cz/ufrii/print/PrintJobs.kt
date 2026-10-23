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
    }

    /** Prints a user file (PDF or image) from [uri]. */
    suspend fun printFile(
        uri: Uri,
        isImage: Boolean,
        colorMode: ColorMode,
        printer: Printer,
        copies: Int = 1
    ): JobResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val tmpFile = File(context.cacheDir, "print_input.tmp")
            AppLog.acc("Tisk ${if (isImage) "obrázku" else "PDF"} → ${printer.name} ${printer.ip}:${printer.port} ($colorMode)")
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@withContext fail(t0, "Nelze otevřít soubor", "openInputStream($uri) == null")
                input.use { inp -> tmpFile.outputStream().use { out -> inp.copyTo(out) } }
                AppLog.i("Vstup zkopírován (${tmpFile.length()} B)")

                val launcher = ProotLauncher(context)
                val detail = if (isImage) {
                    val bmp = decodeImage(tmpFile)
                        ?: return@withContext fail(t0, "Obrázek nelze načíst", "BitmapFactory vrátil null")
                    try {
                        launcher.printImage(bmp, colorMode, printer.ip, printer.port)
                    } finally {
                        bmp.recycle()
                    }
                } else {
                    ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        launcher.printPdf(pfd, colorMode, printer.ip, printer.port, copies)
                    }
                }
                finish(t0, detail)
            } catch (e: Throwable) {
                // Throwable: OutOfMemoryError on huge photos must not crash the app.
                Log.e(TAG, "[job] failed", e)
                fail(t0, "${e.javaClass.simpleName}: ${e.message}", e.stackTraceToString())
            } finally {
                // S2: don't leave the shared document in cache.
                runCatching { tmpFile.delete() }
            }
        }
    }

    /** Prints the bundled assets/sample.pdf test page. */
    suspend fun printTestPage(colorMode: ColorMode, printer: Printer): JobResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val tmpFile = File(context.cacheDir, "sample_test.tmp")
            AppLog.acc("Testovací stránka → ${printer.name} ${printer.ip}:${printer.port} ($colorMode)")
            try {
                context.assets.open("sample.pdf").use { inp ->
                    tmpFile.outputStream().use { out -> inp.copyTo(out) }
                }
                val detail = ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    ProotLauncher(context).printPdf(pfd, colorMode, printer.ip, printer.port)
                }
                finish(t0, detail)
            } catch (e: Throwable) {
                Log.e(TAG, "[job] test page failed", e)
                fail(t0, "${e.javaClass.simpleName}: ${e.message}", e.stackTraceToString())
            } finally {
                runCatching { tmpFile.delete() }
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
            val steps: List<Pair<String, () -> String>> = listOf(
                "Zařízení a knihovny" to launcher::diagnostics,
                "Přímý exec" to launcher::runBinaryDirect,
                "ptrace sonda (vývojová)" to launcher::runPtraceProbe,
                "proot" to launcher::runProotTest,
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
    private fun finish(t0: Long, detail: String): JobResult {
        val ok = detail.contains("exitCode=0") && detail.contains("socket OK:")
        val msg = if (ok) "OK" else shortReason(detail)
        if (ok) AppLog.ok("Vytištěno") else AppLog.err("Tisk selhal: $msg")
        AppLog.dim(detail)
        return JobResult(ok, msg, detail, System.currentTimeMillis() - t0)
    }

    private fun fail(t0: Long, msg: String, detail: String): JobResult {
        AppLog.err("Tisk selhal: $msg")
        AppLog.dim(detail)
        return JobResult(false, msg, detail, System.currentTimeMillis() - t0)
    }

    /** First meaningful failure line of a ProotLauncher summary. */
    private fun shortReason(detail: String): String =
        detail.lines().firstOrNull {
            it.contains("Exception") || it.startsWith("FAIL") || it.contains("CHYBA") ||
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
