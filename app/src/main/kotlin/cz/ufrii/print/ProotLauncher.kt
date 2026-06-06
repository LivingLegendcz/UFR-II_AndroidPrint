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
        destFile.setReadable(true, false)
        destFile.setWritable(true, false)
        destFile.setExecutable(true, false)
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
     * @param printerIp  Printer host.
     * @param port       Printer RAW port (9100).
     * @param feed       Called on the feeder thread; should write raster bytes to
     *                   [OutputStream] and return when done (or throw on error).
     *                   The stream is wrapped in BufferedOutputStream; it is
     *                   flushed+closed after [feed] returns (or throws).
     */
    private fun runFilterPipeline(
        colorMode: ColorMode,
        printerIp: String,
        port: Int,
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

        val optionsStr = when (colorMode) {
            ColorMode.COLOR ->
                "finishings=3 number-up=1 print-color-mode=color CNColorMode=color CNDraftMode=False"
            ColorMode.MONO ->
                "finishings=3 number-up=1 print-color-mode=monochrome CNColorMode=mono CNDraftMode=False"
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
            "-w", "/",
            "/usr/lib/cups/filter/rastertoufr2",
            "1",
            "androiduser",
            "androidprint",
            "1",
            optionsStr
        )

        Log.i(TAG, "[filter] cmd: ${cmd.joinToString(" ")}")
        Log.i(TAG, "[filter] colorMode=$colorMode printer=$printerIp:$port")

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
                        put("PPD", "/etc/cups/ppd/CanonMF8030.ppd")
                        put("CONTENT_TYPE", "application/vnd.cups-raster")
                        put("FINAL_CONTENT_TYPE", "application/vnd.cups-raster")
                        put("PRINTER", "CanonMF8030")
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
                        }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            bos.write(b, off, len)
                            rasterBytesFed += len
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
                                }
                                sockOut.flush()
                            }
                        }
                    }
                    socketResultMsg = "socket OK: $ufrBytesSent UFR-II bajtů odesláno na $printerIp:$port"
                    Log.i(TAG, "[filter] socket done ufr_bytes=$ufrBytesSent")
                } catch (e: ConnectException) {
                    socketResultMsg = "socket ConnectException: ${e.message}\n  >> Zkontroluj WiFi a IP tiskárny 192.168.0.62"
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

            val timedOut = !process.waitFor(120, TimeUnit.SECONDS)
            if (timedOut) {
                process.destroyForcibly()
                Log.w(TAG, "[filter] warn reason=timeout deadline_ms=120000")
            }

            feederThread.join(5000)
            socketThread.join(10000)
            stderrThread.join(5000)

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
        printerIp: String = "192.168.0.62",
        port: Int = 9100
    ): String {
        Log.i(TAG, "[filter] runProotFilter rasterFile=${rasterFile.absolutePath} printer=$printerIp:$port")
        if (!rasterFile.exists()) {
            return "FAIL: raster soubor nenalezen: ${rasterFile.absolutePath}"
        }
        val result = runFilterPipeline(ColorMode.COLOR, printerIp, port) { out ->
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
     */
    fun printPdf(
        pdfFd: ParcelFileDescriptor,
        colorMode: ColorMode,
        printerIp: String = "192.168.0.62",
        port: Int = 9100
    ): String {
        Log.i(TAG, "[filter] printPdf colorMode=$colorMode printer=$printerIp:$port")
        val result = runFilterPipeline(colorMode, printerIp, port) { out ->
            CupsRasterWriter(context).writePdf(out, pdfFd) { rowsDone ->
                Log.d(TAG, "[raster] pdf rows=$rowsDone")
            }
        }
        return buildString {
            appendLine("=== TISK PDF (rastertoufr2) ===")
            appendLine("colorMode=$colorMode  printer=$printerIp:$port")
            append(result)
        }
    }

    /**
     * Renders a [Bitmap] (via [CupsRasterWriter.writeImage]) and prints it.
     */
    fun printImage(
        bitmap: Bitmap,
        colorMode: ColorMode,
        printerIp: String = "192.168.0.62",
        port: Int = 9100
    ): String {
        Log.i(TAG, "[filter] printImage colorMode=$colorMode printer=$printerIp:$port")
        val result = runFilterPipeline(colorMode, printerIp, port) { out ->
            CupsRasterWriter(context).writeImage(out, bitmap) { rowsDone ->
                Log.d(TAG, "[raster] image rows=$rowsDone")
            }
        }
        return buildString {
            appendLine("=== TISK OBRÁZKU (rastertoufr2) ===")
            appendLine("colorMode=$colorMode  printer=$printerIp:$port")
            appendLine("obraz: ${bitmap.width}×${bitmap.height} px")
            append(result)
        }
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
