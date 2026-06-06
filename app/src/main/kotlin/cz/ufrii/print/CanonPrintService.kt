package cz.ufrii.print

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintJobId
import android.print.PrinterId
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import cz.ufrii.print.ColorMode
import cz.ufrii.print.ProotLauncher
import cz.ufrii.print.RootfsManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

private const val TAG = "CanonPrint"
private const val LOG_MAX_BYTES = 256 * 1024L   // 256 KB cap for printservice.log

/**
 * Android PrintService that routes print jobs through the proot/rastertoufr2
 * pipeline implemented in [ProotLauncher].
 *
 * One printer job runs at a time ([printExecutor] is a single-thread pool)
 * because the native filter (rastertoufr2 + cnjbigufr2) is not concurrency-
 * safe and we open only one RAW TCP socket to the printer per job.
 *
 * Flow:
 *   [onCreatePrinterDiscoverySession] → creates [CanonPrinterDiscoverySession]
 *   [onCreate]                        → warm-ups rootfs extraction (non-fatal)
 *   [onPrintJobQueued]                → validates, dups PDF fd, submits to pool
 *   [onRequestCancelPrintJob]         → cancels queued future (running jobs
 *                                       finish their current page — the native
 *                                       pipeline has no mid-stream abort hook)
 */
class CanonPrintService : PrintService() {

    companion object {
        /**
         * Single-thread executor: serialises all print jobs so only one
         * rastertoufr2 process and one printer socket are live at a time.
         */
        private val printExecutor: ExecutorService = Executors.newSingleThreadExecutor()

        /** Marshal PrintJob state changes to the main thread (framework requirement). */
        private val main = Handler(Looper.getMainLooper())
    }

    // Active futures keyed by PrintJobId so cancellation can interrupt them
    private val activeFutures = ConcurrentHashMap<PrintJobId, Future<*>>()

    // -------------------------------------------------------------------------
    // Persistent file logger — writes timestamped lines to printservice.log
    // -------------------------------------------------------------------------

    /** Append a timestamped line to filesDir/printservice.log, capped at ~256 KB. */
    @Synchronized
    private fun svcLog(line: String) {
        Log.i(TAG, line)
        try {
            val logFile = java.io.File(filesDir, "printservice.log")
            if (logFile.exists() && logFile.length() > LOG_MAX_BYTES) {
                logFile.delete()
            }
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            logFile.appendText("[$ts] $line\n")
        } catch (_: Exception) {}
    }

    /** Show a Toast on the main thread. */
    private fun toast(msg: String) {
        main.post {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // The one discovery session created by the framework; we keep a reference
    // so onPrintJobQueued can resolve PrinterId → Printer
    @Volatile
    private var discoverySession: CanonPrinterDiscoverySession? = null

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "[service] onCreate")

        // Pre-warm rootfs extraction in the background so the first print job
        // doesn't have to wait for it.  Failure here is non-fatal — the actual
        // print job will retry ensureExtracted() itself.
        printExecutor.submit {
            try {
                RootfsManager(this).ensureExtracted { msg ->
                    Log.d(TAG, "[service] warm-up rootfs: $msg")
                }
                Log.i(TAG, "[service] warm-up rootfs done")
            } catch (e: Exception) {
                Log.w(TAG, "[service] warm-up rootfs failed (non-fatal): ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        Log.d(TAG, "[service] onCreatePrinterDiscoverySession")
        val session = CanonPrinterDiscoverySession(this)
        discoverySession = session
        return session
    }

    // -------------------------------------------------------------------------
    // Job queueing
    // -------------------------------------------------------------------------

    override fun onPrintJobQueued(printJob: PrintJob) {
        val jobId = printJob.id
        Log.i(TAG, "[service] onPrintJobQueued jobId=$jobId")
        svcLog("onPrintJobQueued jobId=$jobId")
        svcLog("=== UFR-II v${appVersion()} PrintService — job=$jobId ===")

        // 1. Transition to started immediately (still on binder thread)
        printJob.start()

        // 2. Resolve printer
        val printerId: PrinterId? = printJob.info.printerId
        val printer: cz.ufrii.print.Printer? =
            printerId?.let { discoverySession?.printerFor(it) }
        if (printer == null) {
            Log.e(TAG, "[service] job=$jobId printer not found localId=${printerId?.localId}")
            svcLog("PRINTER NOT FOUND for printerId localId=${printerId?.localId} — fail")
            toast("UFR-II: tiskárna nenalezena")
            printJob.fail("Tiskárna není dostupná")
            return
        }
        Log.d(TAG, "[service] job=$jobId → printer=${printer.name} ${printer.ip}:${printer.port}")
        svcLog("printer resolved: ${printer.name} ${printer.ip}:${printer.port} (source=${printer.source})")

        // 3. Determine colour mode from job attributes
        val attrs: PrintAttributes = printJob.info.attributes
        val colorMode: ColorMode = if (attrs.colorMode == PrintAttributes.COLOR_MODE_MONOCHROME) {
            ColorMode.MONO
        } else {
            ColorMode.COLOR
        }
        svcLog("colorMode=$colorMode mediaSize=${attrs.mediaSize?.id}")

        // 4. Media size validation (null → any size → allow through)
        val ms = attrs.mediaSize?.asPortrait()
        if (ms != null && ms != PrintAttributes.MediaSize.ISO_A4) {
            Log.w(TAG, "[service] job=$jobId unsupported media=${ms.id} — rejecting")
            svcLog("media not A4 (${ms.id}) — fail")
            toast("UFR-II: nepodporovaný formát — ${ms.id}")
            printJob.fail("Podporováno jen A4")
            return
        }

        // 5. Capture + dup the PDF file descriptor NOW on the binder thread.
        //    document.data may be revoked once we return from onPrintJobQueued,
        //    so we must either dup the fd or copy the bytes before we return.
        val pfd: ParcelFileDescriptor? = dupOrCopyPdf(printJob, jobId)
        if (pfd == null) {
            svcLog("FAILED to read document — fail")
            printJob.fail("Nelze přečíst dokument")
            return
        }

        // 6. Optional progress indication
        if (Build.VERSION.SDK_INT >= 24) {
            @Suppress("NewApi")
            printJob.setStatus("Tisknu na ${printer.name}…" as CharSequence)
        }

        // 7. Submit to single-thread executor
        val future = printExecutor.submit {
            // Clear any stale interrupt flag left on this shared single-thread worker by a
            // previously cancelled job — otherwise the new job's blocking I/O dies with
            // "read interrupted by closed() on another thread".
            Thread.interrupted()
            try {
                Log.i(TAG, "[service] job=$jobId start print colorMode=$colorMode ip=${printer.ip}:${printer.port}")
                svcLog("spouštím tisk → ${printer.ip}:${printer.port} colorMode=$colorMode")
                val result = ProotLauncher(this).printPdf(pfd, colorMode, printer.ip, printer.port)
                try { pfd.close() } catch (e: Exception) {
                    Log.w(TAG, "[service] job=$jobId pfd.close failed: ${e.message}")
                }

                val ok = result.contains("exitCode=0") && result.contains("socket OK:")
                val shortMsg = shortMsg(result)
                Log.i(TAG, "[service] job=$jobId result ok=$ok shortMsg=$shortMsg")
                svcLog("printPdf result:\n$result")
                svcLog("ok=$ok")

                main.post {
                    activeFutures.remove(jobId)
                    if (printJob.isQueued || printJob.isStarted) {
                        // isQueued/isStarted — job still active, not cancelled
                        if (ok) {
                            printJob.complete()
                            Log.i(TAG, "[service] job=$jobId complete")
                            svcLog("HOTOVO: job=$jobId complete")
                            toast("UFR-II: vytištěno ✓")
                        } else {
                            printJob.fail(shortMsg)
                            Log.w(TAG, "[service] job=$jobId failed: $shortMsg")
                            svcLog("CHYBA: job=$jobId $shortMsg")
                            toast("UFR-II: chyba — $shortMsg")
                        }
                    } else {
                        Log.d(TAG, "[service] job=$jobId no longer active when result arrived — ignoring")
                        svcLog("job=$jobId no longer active when result arrived — ignoring")
                    }
                }
            } catch (e: InterruptedException) {
                // Cancelled via onRequestCancelPrintJob — close fd and bail
                Log.i(TAG, "[service] job=$jobId interrupted (cancelled)")
                svcLog("job=$jobId interrupted (cancelled)")
                try { pfd.close() } catch (_: Exception) {}
                main.post { activeFutures.remove(jobId) }
            } catch (e: Exception) {
                Log.e(TAG, "[service] job=$jobId exception: ${e.javaClass.simpleName}: ${e.message}")
                svcLog("EXCEPTION job=$jobId ${e.javaClass.simpleName}: ${e.message}")
                try { pfd.close() } catch (_: Exception) {}
                val msg = "${e.javaClass.simpleName}: ${e.message}"
                toast("UFR-II: výjimka — $msg")
                main.post {
                    activeFutures.remove(jobId)
                    if (printJob.isQueued || printJob.isStarted) {
                        printJob.fail(msg)
                    }
                }
            }
        }

        activeFutures[jobId] = future
    }

    // -------------------------------------------------------------------------
    // Cancellation
    // -------------------------------------------------------------------------

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        val jobId = printJob.id
        Log.i(TAG, "[service] onRequestCancelPrintJob jobId=$jobId")
        val future = activeFutures.remove(jobId)
        if (future != null) {
            // Use cancel(false) — cooperative cancel only.  We intentionally do NOT
            // interrupt the shared single-thread worker: interrupting closes open I/O
            // file-descriptors mid-transfer, and the leftover interrupt flag on the
            // reused thread then poisons the very next print job with
            // "read interrupted by closed() on another thread".
            future.cancel(false)
            Log.d(TAG, "[service] job=$jobId future.cancel(false) called (no interrupt)")
        }
        printJob.cancel()
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Copies [printJob.document.data] into a regular cache file and returns a
     * READ-ONLY [ParcelFileDescriptor] over it.
     *
     * We deliberately DO NOT use ParcelFileDescriptor.dup(): the spooler's fd is
     * a non-seekable stream, dup() "succeeds" but yields a non-seekable fd, and
     * PdfRenderer then throws "File descriptor not seekable" (the feeder dies
     * after a few bytes → 120 s timeout → a bogus "socket error" downstream).
     * A regular file IS seekable — this mirrors MainActivity.doPrint(), the path
     * that already prints successfully.
     *
     * MUST be called on the binder thread before returning from
     * [onPrintJobQueued], because the framework may revoke the document fd
     * after we return.
     */
    private fun dupOrCopyPdf(printJob: PrintJob, jobId: PrintJobId): ParcelFileDescriptor? {
        val data: ParcelFileDescriptor? = printJob.document.data
        if (data == null) {
            Log.e(TAG, "[service] job=$jobId document.data is null")
            svcLog("FAILED to read document (document.data is null)")
            return null
        }
        return try {
            val tmp = File(cacheDir, "printjob_${jobId}.pdf")
            ParcelFileDescriptor.AutoCloseInputStream(data).use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            Log.d(TAG, "[service] job=$jobId copied to seekable tmp=${tmp.absolutePath} size=${tmp.length()}")
            svcLog("PDF zkopírováno do ${tmp.name} (${tmp.length()} B) — seekovatelný fd")
            tmp.deleteOnExit()
            pfd
        } catch (e: Exception) {
            Log.e(TAG, "[service] job=$jobId copy failed: ${e.message}")
            svcLog("FAILED to read document (copy failed: ${e.message})")
            null
        }
    }

    /**
     * Extracts a short human-readable error line from [ProotLauncher.printPdf]'s
     * multi-line diagnostic output.
     *
     * Priority:
     *   1. The "UFR-II výstup:" line — carries the socket result message.
     *   2. The first line that contains a known failure keyword.
     *   3. The first non-blank line of the output.
     *   4. Generic fallback.
     */
    private fun shortMsg(result: String): String {
        val lines = result.lines().map { it.trim() }.filter { it.isNotBlank() }

        // 1. UFR-II output line
        lines.firstOrNull { it.startsWith("UFR-II výstup:") }
            ?.removePrefix("UFR-II výstup:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.take(200) }

        // 2. Known failure keywords
        val failKeywords = listOf("socket ConnectException", "socket Timeout", "EXCEPTION", "FAIL:", "CHYBA")
        lines.firstOrNull { line -> failKeywords.any { kw -> line.contains(kw) } }
            ?.let { return it.take(200) }

        // 3. First non-blank line
        return lines.firstOrNull()?.take(200) ?: "Tisk selhal (neznámá chyba)"
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
}
