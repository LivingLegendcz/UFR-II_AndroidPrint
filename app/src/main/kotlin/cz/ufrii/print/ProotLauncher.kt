package cz.ufrii.print

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "CanonPrint"

enum class ColorMode { COLOR, MONO }

class ProotLauncher(private val context: Context) {

    private companion object {
        /** No bytes to the filter or the printer for this long = the printer holds the job back (scan mode). */
        const val STALL_MS = 15_000L
        /** A stall this long ends the job (the printer is not going to take it). */
        const val STALL_GIVE_UP_MS = 10 * 60_000L
    }


    val nativeLibDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    val prootBinary: File
        get() = File(nativeLibDir, "libproot.so")

    // -------------------------------------------------------------------------
    // Asset extraction helper
    // -------------------------------------------------------------------------

    fun extractAsset(assetPath: String, destFile: File) {
        if (destFile.exists()) {
            Log.d(TAG, "[extract] skip assetPath=$assetPath reason=already_exists")
            return
        }
        destFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output -> input.copyTo(output) }
        }
        // S6: owner-only — the guest runs under our app uid, no other uid needs access.
        destFile.setReadable(true, true)
        destFile.setWritable(true, true)
        destFile.setExecutable(true, true)
        Log.i(TAG, "[extract] done assetPath=$assetPath dest=${destFile.absolutePath} size=${destFile.length()}")
    }

    // -------------------------------------------------------------------------
    // Direct exec smoke test (NDK binary, no proot)
    // -------------------------------------------------------------------------

    fun runBinaryDirect(): String {
        val bin = File(nativeLibDir, "libtest_hello.so")
        Log.d(TAG, "[direct] start bin=${bin.absolutePath} exists=${bin.exists()} canExec=${bin.canExecute()}")

        if (!bin.exists()) {
            Log.e(TAG, "[direct] fail reason=binary_not_found path=${bin.absolutePath}")
            return "FAIL: libtest_hello.so nenalezeno v ${bin.absolutePath}"
        }

        val tStart = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder(bin.absolutePath)
                .redirectErrorStream(true)
                .start()

            val lines = mutableListOf<String>()
            process.inputStream.bufferedReader().forEachLine { line ->
                Log.i(TAG, "[direct] > $line")
                lines.add(line)
            }
            val exitCode = process.waitFor()
            val elapsedMs = System.currentTimeMillis() - tStart

            Log.i(TAG, "[direct] done exitCode=$exitCode elapsed_ms=$elapsedMs lines=${lines.size}")
            "=== PŘÍMÝ EXEC (bez proot) ===\nexitCode=$exitCode  elapsed=${elapsedMs}ms\n${lines.joinToString("\n")}"
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - tStart
            Log.e(TAG, "[direct] exception elapsed_ms=$elapsedMs class=${e.javaClass.simpleName} msg=${e.message}")
            "EXCEPTION (direct): ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // -------------------------------------------------------------------------
    // Proot smoke test (test-hello binary)
    // -------------------------------------------------------------------------

    fun runProotTest(): String {
        val proot = prootBinary
        Log.d(TAG, "[proot] start prootBin=${proot.absolutePath} exists=${proot.exists()} canExec=${proot.canExecute()}")

        if (!proot.exists()) {
            Log.e(TAG, "[proot] fail reason=proot_not_found path=${proot.absolutePath}")
            return "FAIL: libproot.so nenalezeno v ${proot.absolutePath}\n" +
                   "Zkontroluj: android:extractNativeLibs=true + useLegacyPackaging=true"
        }

        val rootfsDir = File(context.filesDir, "rootfs").also { rf ->
            rf.mkdirs()
            listOf("proc", "dev", "sys", "system", "spike").forEach { d ->
                File(rf, d).mkdirs()
            }
        }
        Log.d(TAG, "[proot] rootfs=${rootfsDir.absolutePath}")

        val guestInRootfs = File(rootfsDir, "spike/test-hello")
        extractAsset("spike/test-hello", guestInRootfs)

        val prootTmpDir = File(context.cacheDir, "proot_tmp").also { it.mkdirs() }
        val loaderBin = File(nativeLibDir, "libproot_loader.so")
        val tallocBin = File(nativeLibDir, "libtalloc.so")

        Log.d(TAG, "[proot] loader=${loaderBin.absolutePath} exists=${loaderBin.exists()}")
        Log.d(TAG, "[proot] talloc=${tallocBin.absolutePath} exists=${tallocBin.exists()}")

        val cmd = listOf(
            proot.absolutePath,
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/proc:/proc",
            "-b", "/dev:/dev",
            "-b", "/sys:/sys",
            "-b", "/system:/system",
            "--link2symlink",
            "-w", "/",
            "/spike/test-hello"
        )

        Log.i(TAG, "[proot] cmd ${cmd.joinToString(" ")}")

        val tStart = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .apply {
                    environment().apply {
                        put("PROOT_NO_SECCOMP", "1")
                        put("PROOT_TMP_DIR", prootTmpDir.absolutePath)
                        put("TMPDIR", context.cacheDir.absolutePath)
                        put("HOME", "/")
                        put("PATH", "/system/bin:/system/xbin")
                        put("LD_LIBRARY_PATH", nativeLibDir.absolutePath)
                        if (loaderBin.exists()) {
                            put("PROOT_LOADER", loaderBin.absolutePath)
                            Log.d(TAG, "[proot] env PROOT_LOADER=${loaderBin.absolutePath}")
                        } else {
                            Log.w(TAG, "[proot] warn PROOT_LOADER not set reason=file_missing path=${loaderBin.absolutePath}")
                        }
                    }
                }
                .start()

            val output = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + 15_000L

            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    Log.i(TAG, "[proot] > $line")
                    output.appendLine(line)
                } else if (!process.isAlive) {
                    break
                } else {
                    Thread.sleep(50)
                }
            }

            val timedOut = process.isAlive
            val exitCode = if (timedOut) {
                process.destroyForcibly()
                Log.w(TAG, "[proot] warn reason=timeout deadline_ms=15000")
                -1
            } else {
                process.waitFor()
            }

            val elapsedMs = System.currentTimeMillis() - tStart
            Log.i(TAG, "[proot] done exitCode=$exitCode elapsed_ms=$elapsedMs timed_out=$timedOut")

            "=== PROOT TEST ===\nexitCode=$exitCode  elapsed=${elapsedMs}ms\n$output"

        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - tStart
            Log.e(TAG, "[proot] exception elapsed_ms=$elapsedMs class=${e.javaClass.simpleName} msg=${e.message}")
            "EXCEPTION (proot): ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // -------------------------------------------------------------------------
    // Core filter pipeline (private)
    // -------------------------------------------------------------------------

    /**
     * Runs rastertoufr2 under proot, feeds raster bytes from [feed] lambda on a
     * feeder thread, drains UFR-II stdout to the printer TCP socket, and drains
     * stderr to a list.  Returns a human-readable summary string.
     *
     * @param colorMode  Selects the CUPS options string passed as filter arg #5.
     * @param source     PPD InputSlot (Auto / Manual / Cas1), also in arg #5.
     * @param printerIp  Printer host.
     * @param port       Printer RAW port (9100).
     * @param copies     Passed verbatim as rastertoufr2 arg #4. NOTE: the filter
     *                   ignores it (1 sheet printed for copies=2) — callers render
     *                   copies into the raster stream and pass 1 here.
     * @param feed       Called on the feeder thread; should write raster bytes to
     *                   [OutputStream] and return when done (or throw on error).
     *                   The stream is wrapped in BufferedOutputStream; it is
     *                   flushed+closed after [feed] returns (or throws).
     */
    private fun runFilterPipeline(
        colorMode: ColorMode,
        source: PaperSource,
        printerIp: String,
        port: Int,
        copies: Int,
        ppd: PpdInfo = PpdCatalog.byFile(context, null),
        duplex: Boolean = false,
        feed: (OutputStream) -> Unit
    ): String {

        // Extrahuj rootfs (idempotentní)
        val rootfsDir = try {
            RootfsManager(context).ensureExtracted { msg ->
                Log.i(TAG, "[filter] rootfs: $msg")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[filter] rootfs extraction failed: ${e.message}")
            return "FAIL: extrakce rootfs selhala: ${e.javaClass.simpleName}: ${e.message}"
        }

        val proot = prootBinary
        if (!proot.exists()) {
            return "FAIL: libproot.so nenalezeno v ${proot.absolutePath}"
        }

        val prootTmpDir = File(context.cacheDir, "proot_tmp").also { it.mkdirs() }
        val loaderBin = File(nativeLibDir, "libproot_loader.so")

        // Options only as this model's PPD offers them (an unknown InputSlot would silently fall
        // back to Auto; CNColorMode is absent on mono models).
        val color = colorMode == ColorMode.COLOR
        val optionsStr = buildList {
            add("finishings=3"); add("number-up=1")
            add("print-color-mode=" + if (color) "color" else "monochrome")
            if (ppd.colorModes.isNotEmpty()) add("CNColorMode=" + if (color && "color" in ppd.colorModes) "color" else "mono")
            add("CNDraftMode=False")
            add("InputSlot=" + if (source.ppd in ppd.slots) source.ppd else "Auto")
            if (duplex && ppd.duplex) add("Duplex=DuplexNoTumble")
        }.joinToString(" ")
        val ppdPath = try {
            PpdCatalog.guestPath(context, rootfsDir, ppd)
        } catch (e: Exception) {
            return "FAIL: PPD ${ppd.file}: ${e.message}"
        }

        val cmd = listOf(
            proot.absolutePath,
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/proc:/proc",
            "-b", "/dev:/dev",
            "-b", "/sys:/sys",
            "-b", "/system:/system",
            "--link2symlink",
            // PTRACE_O_EXITKILL: killing proot (cancel, stall) also kills the filter it runs —
            // otherwise the orphaned rastertoufr2 kept the pipes open and the job never ended.
            "--kill-on-exit",
            "-w", "/",
            "/usr/lib/cups/filter/rastertoufr2",
            "1",                              // job-id
            "androiduser",                    // user
            "androidprint",                   // title
            copies.coerceAtLeast(1).toString(),  // copies (F2 — argv #4)
            optionsStr
        )

        Log.i(TAG, "[filter] cmd: ${cmd.joinToString(" ")}")
        Log.i(TAG, "[filter] colorMode=$colorMode source=$source printer=$printerIp:$port copies=$copies ppd=${ppd.file} duplex=$duplex")

        val tStart = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder(cmd)
                .apply {
                    environment().apply {
                        // proot is statically linked — it does NOT need LD_LIBRARY_PATH for itself.
                        // These three vars are proot's own transport / seccomp vars (host-side).
                        put("PROOT_NO_SECCOMP", "1")
                        put("PROOT_TMP_DIR", prootTmpDir.absolutePath)
                        put("TMPDIR", context.cacheDir.absolutePath)
                        if (loaderBin.exists()) {
                            put("PROOT_LOADER", loaderBin.absolutePath)
                        } else {
                            Log.w(TAG, "[filter] warn PROOT_LOADER not set path=${loaderBin.absolutePath}")
                        }
                        // GUEST LD_LIBRARY_PATH — these paths are INSIDE the proot guest (glibc
                        // Debian arm64 multiarch layout).  rastertoufr2 and its child cnjbigufr2
                        // both need this so that dlopen("libjbig.so*") and dlopen("libcups*.so*")
                        // resolve without ldconfig cache.
                        // NOTE: do NOT include nativeLibDir (bionic) here — that would confuse glibc.
                        put("LD_LIBRARY_PATH",
                            "/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib:/lib:/usr/lib/cups/filter")
                        // Guest env (paths inside rootfs)
                        put("PPD", ppdPath)
                        put("CONTENT_TYPE", "application/vnd.cups-raster")
                        put("FINAL_CONTENT_TYPE", "application/vnd.cups-raster")
                        put("PRINTER", "Canon")
                        put("CUPS_DATADIR", "/usr/share/cups")
                        put("CUPS_SERVERROOT", "/etc/cups")
                        put("CUPS_SERVERBIN", "/usr/lib/cups")
                        put("CUPS_CACHEDIR", "/var/cache/cups")
                        put("CUPS_REQUESTROOT", "/var/spool/cups")
                        put("TMPDIR", "/var/spool/cups/tmp")
                        put("HOME", "/var/spool/cups/tmp")
                        put("RIP_CACHE", "/var/cache/cups")
                        put("PATH", "/usr/lib/cups/filter:/usr/bin:/usr/sbin:/bin")
                        put("LANG", "en.UTF-8")
                    }
                }
                .start()

            var rasterBytesFed = 0L
            var ufrBytesSent = 0L
            var socketResultMsg = "socket: nepřipojen"
            val sockRef = AtomicReference<Socket?>(null)
            // Bytes moved in either direction; the stall watchdog below watches it.
            val progress = java.util.concurrent.atomic.AtomicLong(0)
            val token = CancelToken.current()
            token?.bind {
                Log.i(TAG, "[filter] cancel requested — killing filter, closing socket")
                process.destroyForcibly()
                runCatching { sockRef.get()?.close() }
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
            }
            val stderrLines = mutableListOf<String>()
            val feedError = AtomicReference<Throwable?>(null)

            // Thread 1: feeder — feed lambda → process stdin
            val feederThread = Thread {
                val bos = BufferedOutputStream(process.outputStream, 65536)
                try {
                    // Wrap with a counting stream
                    val countingOut = object : OutputStream() {
                        override fun write(b: Int) {
                            bos.write(b)
                            rasterBytesFed++
                            progress.incrementAndGet()
                        }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            bos.write(b, off, len)
                            rasterBytesFed += len
                            progress.addAndGet(len.toLong())
                        }
                        override fun flush() = bos.flush()
                        override fun close() = bos.close()
                    }
                    feed(countingOut)
                    countingOut.flush()
                    Log.i(TAG, "[filter] feeder done raster_bytes=$rasterBytesFed")
                } catch (t: Throwable) {
                    feedError.set(t)
                    Log.e(TAG, "[filter] feeder EXCEPTION class=${t.javaClass.name} msg=${t.message}", t)
                } finally {
                    // Always close stdin so the filter process unblocks
                    try { bos.flush() } catch (_: Throwable) {}
                    try { bos.close() } catch (_: Throwable) {}
                    try { process.outputStream.close() } catch (_: Throwable) {}
                }
            }

            // Thread 2: socket drainer — process stdout → printer TCP
            val socketThread = Thread {
                try {
                    val sock = Socket()
                    sockRef.set(sock)
                    sock.connect(InetSocketAddress(printerIp, port), 5000)
                    Log.i(TAG, "[filter] socket connected $printerIp:$port")
                    sock.use { s ->
                        s.getOutputStream().use { sockOut ->
                            process.inputStream.use { pIn ->
                                val buf = ByteArray(65536)
                                var n: Int
                                while (pIn.read(buf).also { n = it } != -1) {
                                    sockOut.write(buf, 0, n)
                                    ufrBytesSent += n
                                    progress.addAndGet(n.toLong())
                                }
                                sockOut.flush()
                            }
                        }
                    }
                    socketResultMsg = "socket OK: $ufrBytesSent UFR-II bajtů odesláno na $printerIp:$port"
                    Log.i(TAG, "[filter] socket done ufr_bytes=$ufrBytesSent")
                } catch (e: ConnectException) {
                    socketResultMsg = "socket ConnectException: ${e.message}\n  >> Zkontroluj WiFi a IP tiskárny $printerIp"
                    Log.e(TAG, "[filter] socket connect failed: ${e.message}")
                    try { process.inputStream.close() } catch (_: Exception) {}
                } catch (e: SocketTimeoutException) {
                    socketResultMsg = "socket Timeout: ${e.message}\n  >> Tiskárna nereaguje na $printerIp:$port"
                    Log.e(TAG, "[filter] socket timeout: ${e.message}")
                    try { process.inputStream.close() } catch (_: Exception) {}
                } catch (e: Exception) {
                    socketResultMsg = "socket error (${e.javaClass.simpleName}): ${e.message}"
                    Log.e(TAG, "[filter] socket exception: ${e.message}")
                    try { process.inputStream.close() } catch (_: Exception) {}
                }
            }

            // Thread 3: stderr reader
            val stderrThread = Thread {
                try {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        Log.w(TAG, "[filter] stderr: $line")
                        synchronized(stderrLines) { stderrLines.add(line) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[filter] stderr reader error: ${e.message}")
                }
            }

            feederThread.start()
            socketThread.start()
            stderrThread.start()

            // Stall watchdog: a printer that accepts the connection but takes no data (e.g. its
            // panel is in Remote Scanner mode) blocks the socket write, then the filter, then the
            // renderer. No bytes moving for STALL_MS → give up instead of hanging; the overall
            // limit only guards against a runaway filter on huge jobs.
            var stalled = false
            var lastProgress = -1L
            var lastMove = System.currentTimeMillis()
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                val now = System.currentTimeMillis()
                val p = progress.get()
                if (p != lastProgress) {
                    lastProgress = p; lastMove = now
                    StallRetry.setWaiting(context, null)
                }
                // Not taking data: tell the user what to do and keep the connection — the data flows
                // again as soon as scanning ends on the panel. Only a very long stall ends the job.
                if (now - lastMove > STALL_MS) StallRetry.setWaiting(context, StallRetry.current ?: "tisk")
                if (now - lastMove > STALL_GIVE_UP_MS) { stalled = true; break }
                if (now - tStart > 30 * 60_000L) break
            }
            StallRetry.setWaiting(context, null)
            val timedOut = process.isAlive
            if (timedOut) {
                process.destroyForcibly()
                runCatching { sockRef.get()?.close() }
                Log.w(TAG, "[filter] warn reason=${if (stalled) "stalled" else "timeout"} raster=$rasterBytesFed ufr=$ufrBytesSent")
            }
            token?.unbind()

            // After a cancel / stall nothing useful is left to wait for.
            val ended = token?.cancelled == true || stalled
            if (ended) runCatching { process.outputStream.close() }
            feederThread.join(if (ended) 1000 else 5000)
            socketThread.join(if (ended) 1000 else 10000)
            stderrThread.join(if (ended) 500 else 5000)

            val exitCode = if (timedOut) -1 else process.exitValue()
            val elapsedMs = System.currentTimeMillis() - tStart

            Log.i(TAG, "[filter] done exitCode=$exitCode elapsed_ms=$elapsedMs raster_bytes=$rasterBytesFed ufr_bytes=$ufrBytesSent timed_out=$timedOut")

            val lastStderr = synchronized(stderrLines) {
                stderrLines.takeLast(20).joinToString("\n")
            }

            val expectedRasterBytes = CupsRasterWriter.CUPS_HEIGHT.toLong() * CupsRasterWriter.BYTES_PER_LINE
            val feederErr = feedError.get()

            buildString {
                appendLine("exitCode=$exitCode  elapsed=${elapsedMs}ms  timedOut=$timedOut")
                if (token?.cancelled == true) appendLine("FAIL: zrušeno")
                else if (stalled) appendLine("FAIL: tiskárna ${STALL_GIVE_UP_MS / 60_000} min nepřijímá data — nejspíš je " +
                    "v režimu skenování (Remote Scanner) nebo zaneprázdněná.")
                appendLine("--- raster generátor ---")
                if (feederErr == null) {
                    appendLine("OK, $rasterBytesFed bajtů zapsáno (očekáváno $expectedRasterBytes na stránku)")
                } else {
                    appendLine("CHYBA po $rasterBytesFed bajtech (očekáváno $expectedRasterBytes na stránku):")
                    appendLine("${feederErr.javaClass.name}: ${feederErr.message}")
                    appendLine(Log.getStackTraceString(feederErr))
                }
                appendLine("UFR-II výstup: $socketResultMsg")
                if (lastStderr.isNotBlank()) {
                    appendLine("--- stderr (posledních ${minOf(20, stderrLines.size)} řádků) ---")
                    appendLine(lastStderr)
                }
            }

        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - tStart
            Log.e(TAG, "[filter] exception elapsed_ms=$elapsedMs class=${e.javaClass.simpleName} msg=${e.message}")
            "EXCEPTION (filter): ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Pipe a pre-made raster file through rastertoufr2 (smoke test).
     * Kept for backward compatibility / manual testing.
     */
    fun runProotFilter(
        rasterFile: File,
        printerIp: String,
        port: Int
    ): String {
        Log.i(TAG, "[filter] runProotFilter rasterFile=${rasterFile.absolutePath} printer=$printerIp:$port")
        if (!rasterFile.exists()) {
            return "FAIL: raster soubor nenalezen: ${rasterFile.absolutePath}"
        }
        val result = runFilterPipeline(ColorMode.COLOR, PaperSource.AUTO, printerIp, port, copies = 1) { out ->
            rasterFile.inputStream().use { it.copyTo(out) }
        }
        return buildString {
            appendLine("=== SMOKE TEST: rastertoufr2 pod proot ===")
            append(result)
        }
    }

    /**
     * Renders a PDF (via [CupsRasterWriter.writePdf]) and prints it.
     * [pdfFd] must be seekable (open from a regular file, not a stream URI).
     * [pages]/[rotate180] select one manual-duplex pass (see [DuplexPlanner]).
     */
    fun printPdf(
        pdfFd: ParcelFileDescriptor,
        colorMode: ColorMode,
        printerIp: String,
        port: Int,
        copies: Int = 1,
        source: PaperSource = PaperSource.AUTO,
        pages: List<Int>? = null,
        rotate180: Boolean = false,
        onPage: ((Int, Int) -> Unit)? = null,
        ppd: PpdInfo = PpdCatalog.byFile(context, null),
        duplex: Boolean = false
    ): String {
        Log.i(TAG, "[filter] printPdf colorMode=$colorMode source=$source printer=$printerIp:$port copies=$copies pages=${pages?.size ?: "all"}")
        // F2: the filter ignores argv copies → copies are rendered into the raster
        // stream instead, and the filter always gets 1 (no double multiplication).
        val result = runFilterPipeline(colorMode, source, printerIp, port, copies = 1, ppd = ppd, duplex = duplex) { out ->
            CupsRasterWriter(context, PageGeometry.fromArea(ppd.a4)).writePdf(out, pdfFd, copies, pages, rotate180, onPage,
                progress = { rowsDone -> Log.v(TAG, "[raster] pdf rows=$rowsDone") }, padOdd = duplex && ppd.duplex)
        }
        return buildString {
            appendLine("=== TISK PDF (rastertoufr2) ===")
            appendLine("colorMode=$colorMode  source=${source.ppd}  printer=$printerIp:$port  copies=$copies  ppd=${ppd.short}" +
                if (duplex && ppd.duplex) "  duplex=auto" else "")
            append(result)
        }
    }

    /**
     * Renders a [Bitmap] (via [CupsRasterWriter.writeImage]) and prints it.
     */
    fun printImage(
        bitmap: Bitmap,
        colorMode: ColorMode,
        printerIp: String,
        port: Int,
        source: PaperSource = PaperSource.AUTO,
        ppd: PpdInfo = PpdCatalog.byFile(context, null)
    ): String {
        Log.i(TAG, "[filter] printImage colorMode=$colorMode source=$source printer=$printerIp:$port")
        val result = runFilterPipeline(colorMode, source, printerIp, port, copies = 1, ppd = ppd) { out ->
            CupsRasterWriter(context, PageGeometry.fromArea(ppd.a4)).writeImage(out, bitmap) { rowsDone ->
                Log.v(TAG, "[raster] image rows=$rowsDone")
            }
        }
        return buildString {
            appendLine("=== TISK OBRÁZKU (rastertoufr2) ===")
            appendLine("colorMode=$colorMode  source=${source.ppd}  printer=$printerIp:$port")
            appendLine("obraz: ${bitmap.width}×${bitmap.height} px")
            append(result)
        }
    }

    /** Prints pages drawn on a Canvas (duplex calibration sheet), always mono. */
    fun printDrawn(
        drawPages: List<(android.graphics.Canvas) -> Unit>,
        printerIp: String,
        port: Int,
        source: PaperSource,
        ppd: PpdInfo = PpdCatalog.byFile(context, null)
    ): String {
        val result = runFilterPipeline(ColorMode.MONO, source, printerIp, port, copies = 1, ppd = ppd) { out ->
            CupsRasterWriter(context, PageGeometry.fromArea(ppd.a4)).writeDrawn(out, drawPages)
        }
        return "=== TISK KALIBRACE (rastertoufr2) ===\nsource=${source.ppd}\n$result"
    }

    // -------------------------------------------------------------------------
    // ptrace probe — direct exec of libptraceprobe.so
    // -------------------------------------------------------------------------

    fun runPtraceProbe(): String {
        val bin = File(nativeLibDir, "libptraceprobe.so")
        Log.d(TAG, "[ptrace_probe] start bin=${bin.absolutePath} exists=${bin.exists()} canExec=${bin.canExecute()}")

        if (!bin.exists()) {
            Log.e(TAG, "[ptrace_probe] fail reason=binary_not_found path=${bin.absolutePath}")
            return "=== PTRACE PROBE ===\nFAIL: libptraceprobe.so nenalezeno v ${bin.absolutePath}"
        }

        val tStart = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder(bin.absolutePath)
                .redirectErrorStream(true)
                .start()

            val lines = mutableListOf<String>()
            val timedOut: Boolean
            val reader = process.inputStream.bufferedReader()

            // Read with 10 s timeout
            val deadline = System.currentTimeMillis() + 10_000L
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    Log.i(TAG, "[ptrace_probe] > $line")
                    lines.add(line)
                } else if (!process.isAlive) {
                    // drain any remaining output
                    reader.readLines().forEach { line ->
                        Log.i(TAG, "[ptrace_probe] > $line")
                        lines.add(line)
                    }
                    break
                } else {
                    Thread.sleep(50)
                }
            }
            timedOut = process.isAlive
            val exitCode = if (timedOut) {
                process.destroyForcibly()
                Log.w(TAG, "[ptrace_probe] warn reason=timeout")
                -1
            } else {
                process.waitFor()
            }
            val elapsedMs = System.currentTimeMillis() - tStart
            Log.i(TAG, "[ptrace_probe] done exitCode=$exitCode elapsed_ms=$elapsedMs timed_out=$timedOut")

            buildString {
                appendLine("=== PTRACE PROBE ===")
                appendLine("exitCode=$exitCode  elapsed=${elapsedMs}ms  timedOut=$timedOut")
                lines.forEach { appendLine(it) }
            }
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - tStart
            Log.e(TAG, "[ptrace_probe] exception elapsed_ms=$elapsedMs class=${e.javaClass.simpleName} msg=${e.message}")
            "=== PTRACE PROBE ===\nEXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // -------------------------------------------------------------------------
    // cnjbigufr2 probe — ground-truth library resolution test
    // -------------------------------------------------------------------------

    /**
     * Runs /usr/bin/cnjbigufr2 under proot with no arguments, using the same
     * environment (including the guest LD_LIBRARY_PATH) as the real print path.
     * Captures stdout + stderr (redirected) for up to 8 seconds.
     *
     * If cnjbigufr2 is missing a shared library the glibc dynamic linker prints:
     *   error while loading shared libraries: <lib>: cannot open shared object file
     * — that output tells us exactly which library to add to the rootfs or symlink.
     *
     * Returns a multi-line string starting with "=== CNJBIGUFR2 PROBE ===" so it
     * can be appended directly to the Diagnostika log.
     */
    fun runCnjbigProbe(): String {
        val proot = prootBinary
        if (!proot.exists()) {
            return "=== CNJBIGUFR2 PROBE ===\nFAIL: libproot.so nenalezeno v ${proot.absolutePath}"
        }

        val rootfsDir = try {
            RootfsManager(context).ensureExtracted { }
        } catch (e: Exception) {
            return "=== CNJBIGUFR2 PROBE ===\nFAIL: rootfs extraction: ${e.message}"
        }

        val prootTmpDir = File(context.cacheDir, "proot_tmp").also { it.mkdirs() }
        val loaderBin = File(nativeLibDir, "libproot_loader.so")

        val cmd = listOf(
            proot.absolutePath,
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/proc:/proc",
            "-b", "/dev:/dev",
            "-b", "/sys:/sys",
            "-b", "/system:/system",
            "--link2symlink",
            "-w", "/",
            "/usr/bin/cnjbigufr2"
            // No arguments — cnjbigufr2 will exit quickly; we just need dlopen to succeed
        )

        Log.i(TAG, "[cnjbig_probe] cmd: ${cmd.joinToString(" ")}")

        val tStart = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)   // merge stderr into stdout so we capture everything
                .apply {
                    environment().apply {
                        put("PROOT_NO_SECCOMP", "1")
                        put("PROOT_TMP_DIR", prootTmpDir.absolutePath)
                        put("TMPDIR", context.cacheDir.absolutePath)
                        if (loaderBin.exists()) put("PROOT_LOADER", loaderBin.absolutePath)
                        // Same guest LD_LIBRARY_PATH as the real print path
                        put("LD_LIBRARY_PATH",
                            "/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib:/lib:/usr/lib/cups/filter")
                        put("PATH", "/usr/bin:/usr/sbin:/bin")
                        put("HOME", "/")
                    }
                }
                .start()

            val output = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + 8_000L

            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    Log.i(TAG, "[cnjbig_probe] > $line")
                    output.appendLine(line)
                } else if (!process.isAlive) {
                    // drain remaining output
                    reader.readLines().forEach { line ->
                        Log.i(TAG, "[cnjbig_probe] > $line")
                        output.appendLine(line)
                    }
                    break
                } else {
                    Thread.sleep(50)
                }
            }

            val timedOut = process.isAlive
            val exitCode = if (timedOut) {
                process.destroyForcibly()
                Log.w(TAG, "[cnjbig_probe] timeout after 8s")
                -1
            } else {
                process.waitFor()
            }
            val elapsedMs = System.currentTimeMillis() - tStart
            Log.i(TAG, "[cnjbig_probe] done exitCode=$exitCode elapsed_ms=$elapsedMs timedOut=$timedOut")

            buildString {
                appendLine("=== CNJBIGUFR2 PROBE ===")
                appendLine("exitCode=$exitCode  elapsed=${elapsedMs}ms  timedOut=$timedOut")
                if (output.isNotBlank()) {
                    appendLine("--- output (stdout+stderr) ---")
                    append(output)
                } else {
                    appendLine("(no output — binary loaded libs OK and exited silently)")
                }
            }
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - tStart
            Log.e(TAG, "[cnjbig_probe] exception elapsed_ms=$elapsedMs: ${e.message}")
            "=== CNJBIGUFR2 PROBE ===\nEXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    fun diagnostics(): String {
        val sb = StringBuilder()

        // ---- Device info ----
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("Zařízení: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        try {
            val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            sb.appendLine("Page size: $pageSize B")
        } catch (e: Exception) {
            sb.appendLine("Page size: CHYBA (${e.message})")
        }
        sb.appendLine()

        // ---- Paths ----
        sb.appendLine("nativeLibDir: ${nativeLibDir.absolutePath}")
        sb.appendLine("filesDir: ${context.filesDir.absolutePath}")
        sb.appendLine("cacheDir: ${context.cacheDir.absolutePath}")
        val files = nativeLibDir.listFiles()
        if (files == null) {
            sb.appendLine("  (nativeLibDir not listable)")
            Log.w(TAG, "[diag] warn nativeLibDir not listable path=${nativeLibDir.absolutePath}")
        } else {
            files.sortedBy { it.name }.forEach { f ->
                sb.appendLine("  ${f.name}  size=${f.length()}  exec=${f.canExecute()}")
                Log.d(TAG, "[diag] file name=${f.name} size=${f.length()} exec=${f.canExecute()}")
            }
        }
        return sb.toString()
    }
}
