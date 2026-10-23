package cz.ufrii.print

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

private const val TAG = "CanonPrint"

class MainActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private var pendingUri: Uri? = null
    private var pendingIsImage: Boolean = false
    private var selectedColorMode: ColorMode = ColorMode.COLOR
    private var lastSvcLogShown: String = ""

    // -------------------------------------------------------------------------
    // UI references
    // -------------------------------------------------------------------------

    private lateinit var tvTitle: TextView
    private lateinit var rgColorMode: RadioGroup
    private lateinit var rbColor: RadioButton
    private lateinit var rbMono: RadioButton
    private lateinit var btnTisk: Button
    private lateinit var btnTestTisk: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLog: TextView
    private lateinit var btnCopyLog: Button
    private lateinit var btnDiag: Button
    private lateinit var btnPovolit: Button
    private lateinit var btnPridatTiskarnu: Button
    private lateinit var btnSvcLog: Button
    private lateinit var btnSvcLogClear: Button
    private lateinit var scrollLog: ScrollView

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Log.i(TAG, "[main] onCreate intent=${intent?.action}")
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        showServiceLogAuto()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "[main] onNewIntent intent=${intent.action}")
        handleIntent(intent)
    }

    private fun showServiceLogAuto() {
        val logFile = java.io.File(filesDir, "printservice.log")
        if (!logFile.exists() || logFile.length() == 0L) return
        val content = logFile.readText()
        if (content == lastSvcLogShown) return
        lastSvcLogShown = content
        log("\n=== AUTO: LOG TISKOVÉ SLUŽBY (po návratu do appky) ===\n$content")
    }

    // -------------------------------------------------------------------------
    // Intent handling
    // -------------------------------------------------------------------------

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    val isImage = isImageMime(intent.type)
                    setPendingFile(uri, isImage, intent.type ?: "?")
                    log("Přijat soubor přes SEND: $uri\ntyp: ${intent.type}")
                } else {
                    log("ACTION_SEND: žádný EXTRA_STREAM URI")
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null) {
                    val mimeType = intent.type
                        ?: contentResolver.getType(uri)
                    val isImage = isImageMime(mimeType)
                    setPendingFile(uri, isImage, mimeType ?: "?")
                    log("Přijat soubor přes VIEW: $uri\ntyp: $mimeType")
                } else {
                    log("ACTION_VIEW: žádný URI")
                }
            }
            Intent.ACTION_MAIN, null -> {
                val v = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
                tvTitle.text = "UFR-II v$v — vyberte soubor nebo použijte TEST TISK"
                log("Spuštěno bez souboru.\nPoužijte 'Sdílet' z jiné aplikace nebo stiskněte TEST TISK.")
            }
            else -> {
                log("Neznámá akce: ${intent.action}")
            }
        }
    }

    private fun isImageMime(mimeType: String?): Boolean =
        mimeType != null && mimeType.startsWith("image/")

    private fun setPendingFile(uri: Uri, isImage: Boolean, mimeType: String) {
        pendingUri = uri
        pendingIsImage = isImage
        val kind = if (isImage) "obrázek" else "PDF"
        tvTitle.text = "UFR-II — $kind připraven k tisku"
        // Derive a short display name from the URI
        val fileName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: uri.toString().substringAfterLast('/').take(40)
        btnTisk.text = "TISK: $fileName"
        btnTisk.isEnabled = true
        btnTisk.visibility = android.view.View.VISIBLE
        log("Soubor nastaven ($kind): $uri\nmimeType=$mimeType")
    }

    // -------------------------------------------------------------------------
    // Print actions
    // -------------------------------------------------------------------------

    private fun doPrint() {
        val uri = pendingUri ?: run {
            log("CHYBA: žádný soubor k tisku")
            return
        }
        pickPrinter { printer ->
            startFileJob(uri, pendingIsImage, effectiveColorMode(printer), printer)
        }
    }

    private fun doTestTisk() {
        pickPrinter { printer -> startTestJob(effectiveColorMode(printer), printer) }
    }

    /**
     * F1: Resolve which printer the share/test path should use.
     * [PrinterStore.loadManual] seeds a default on first run, so the list is normally non-empty.
     * One printer → used directly; multiple → a chooser dialog (defaults to first).
     * Runs on the UI thread (invoked from a button click).
     */
    private fun pickPrinter(onChosen: (Printer) -> Unit) {
        val printers = PrinterStore.loadManual(this)
        when {
            printers.isEmpty() ->
                log("CHYBA: žádná uložená tiskárna. Přidejte ji přes 'Přidat / upravit tiskárnu'.")
            printers.size == 1 -> onChosen(printers[0])
            else -> {
                // Disable buttons while the chooser is open — a second click would start a
                // parallel job sharing print_input.tmp. Re-enabled on cancel; on choice the
                // job itself keeps them busy until it finishes.
                setBusy(true)
                var chosen = false
                val labels = printers.map { "${it.name} (${it.ip}:${it.port})" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Vyberte tiskárnu")
                    .setItems(labels) { _, which ->
                        chosen = true
                        onChosen(printers[which])
                    }
                    .setNegativeButton("Zrušit", null)
                    .setOnDismissListener { if (!chosen) setBusy(false) }
                    .show()
            }
        }
    }

    /** Downgrade COLOR → MONO when the chosen printer is mono-only. */
    private fun effectiveColorMode(printer: Printer): ColorMode =
        if (selectedColorMode == ColorMode.COLOR && !printer.colorCapable) {
            log("Tiskárna '${printer.name}' je jen černobílá → tisknu MONO.")
            ColorMode.MONO
        } else {
            selectedColorMode
        }

    private fun startFileJob(uri: Uri, isImage: Boolean, colorMode: ColorMode, printer: Printer) {
        setBusy(true)
        log("\n--- TISK ZAHÁJEN (${if (isImage) "obrázek" else "PDF"}, $colorMode → ${printer.name} ${printer.ip}:${printer.port}) ---")

        Thread {
            try {
                // Copy URI content to seekable temp file
                log("Kopíruji vstup do temp souboru...")
                val tmpFile = File(cacheDir, "print_input.tmp")
                contentResolver.openInputStream(uri)?.use { input ->
                    tmpFile.outputStream().use { out -> input.copyTo(out) }
                } ?: run {
                    log("CHYBA: nelze otevřít vstupní stream z URI")
                    return@Thread
                }
                log("Temp soubor: ${tmpFile.absolutePath} (${tmpFile.length()} B)")

                val launcher = ProotLauncher(this)

                val result: String = if (isImage) {
                    // Decode bitmap with subsampling to avoid OOM
                    log("Dekóduji obrázek...")
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(tmpFile.absolutePath, opts)
                    val sampleSize = computeInSampleSize(
                        opts.outWidth, opts.outHeight,
                        CupsRasterWriter.CUPS_WIDTH, CupsRasterWriter.CUPS_HEIGHT
                    )
                    log("Rozměry obrázku: ${opts.outWidth}×${opts.outHeight}, inSampleSize=$sampleSize")
                    val decOpts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bmp = BitmapFactory.decodeFile(tmpFile.absolutePath, decOpts)
                        ?: run {
                            log("CHYBA: BitmapFactory vrátil null")
                            return@Thread
                        }
                    // F3: honour EXIF orientation so phone photos print upright.
                    val finalBmp = applyExifRotation(bmp, tmpFile)
                    log("Bitmap dekódován: ${finalBmp.width}×${finalBmp.height}, tisknu...")
                    val r = launcher.printImage(finalBmp, colorMode, printer.ip, printer.port)
                    finalBmp.recycle()
                    r
                } else {
                    val pfd = ParcelFileDescriptor.open(
                        tmpFile, ParcelFileDescriptor.MODE_READ_ONLY
                    )
                    log("PDF otevřeno, spouštím filtr...")
                    try {
                        launcher.printPdf(pfd, colorMode, printer.ip, printer.port)
                    } finally {
                        try { pfd.close() } catch (_: Exception) {}
                    }
                }

                log("\nVÝSLEDEK:\n$result")
            } catch (e: Exception) {
                log("\nVÝJIMKA:\n${stackTraceString(e)}")
            } finally {
                // S2: don't leave the shared document in cache.
                runCatching { File(cacheDir, "print_input.tmp").delete() }
                setBusy(false)
            }
        }.start()
    }

    private fun startTestJob(colorMode: ColorMode, printer: Printer) {
        setBusy(true)
        log("\n--- TEST TISK ZAHÁJEN (vestavěné PDF, $colorMode → ${printer.name} ${printer.ip}:${printer.port}) ---")

        Thread {
            try {
                // Copy built-in sample.pdf asset to seekable temp file
                log("Kopíruji vestavěné sample.pdf...")
                val tmpFile = File(cacheDir, "sample_test.tmp")
                assets.open("sample.pdf").use { input ->
                    tmpFile.outputStream().use { out -> input.copyTo(out) }
                }
                log("Temp soubor: ${tmpFile.absolutePath} (${tmpFile.length()} B)")

                val pfd = ParcelFileDescriptor.open(
                    tmpFile, ParcelFileDescriptor.MODE_READ_ONLY
                )
                val launcher = ProotLauncher(this)
                log("Spouštím rastertoufr2 filtr pod proot...")
                val result = try {
                    launcher.printPdf(pfd, colorMode, printer.ip, printer.port)
                } finally {
                    try { pfd.close() } catch (_: Exception) {}
                }

                log("\nVÝSLEDEK:\n$result")
            } catch (e: Exception) {
                log("\nVÝJIMKA:\n${stackTraceString(e)}")
            } finally {
                // S2: remove the test temp file.
                runCatching { File(cacheDir, "sample_test.tmp").delete() }
                setBusy(false)
            }
        }.start()
    }

    /**
     * F3: Reads EXIF orientation from [file] and returns a re-oriented bitmap.
     * Returns [bmp] unchanged (and not recycled) when no rotation is needed or on error.
     * When a rotated copy is produced, the original [bmp] is recycled.
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
            Log.i(TAG, "[main] EXIF orientation=$orientation applied")
            rotated
        } catch (e: Exception) {
            Log.w(TAG, "[main] EXIF rotation failed (using original): ${e.message}")
            bmp
        }
    }

    private fun doDiag() {
        setBusy(true)
        log("\n--- DIAGNOSTIKA ---")
        Thread {
            try {
                val launcher = ProotLauncher(this)
                log(launcher.diagnostics())
                log("--- Test přímého exec ---")
                log(launcher.runBinaryDirect())
                log("--- ptrace sonda ---")
                log(launcher.runPtraceProbe())
                log("--- Test proot ---")
                log(launcher.runProotTest())
                log("--- cnjbigufr2 probe ---")
                log(launcher.runCnjbigProbe())
                log("--- Diagnostika hotova ---")
            } catch (e: Exception) {
                log("\nVÝJIMKA diagnostika:\n${stackTraceString(e)}")
            } finally {
                setBusy(false)
            }
        }.start()
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun log(msg: String) {
        Log.i(TAG, "[ui] $msg")
        runOnUiThread {
            tvLog.append(msg)
            tvLog.append("\n")
            // Auto-scroll to bottom inside the fixed log box
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun setBusy(busy: Boolean) {
        runOnUiThread {
            if (pendingUri != null) {
                btnTisk.isEnabled = !busy
                btnTisk.visibility = android.view.View.VISIBLE
            }
            btnTestTisk.isEnabled = !busy
            btnCopyLog.isEnabled = !busy
            btnDiag.isEnabled = !busy
            progressBar.visibility = if (busy) ProgressBar.VISIBLE else ProgressBar.GONE
        }
    }

    private fun computeInSampleSize(
        srcW: Int, srcH: Int, maxW: Int, maxH: Int
    ): Int {
        var s = 1
        while (srcW / (s * 2) > maxW || srcH / (s * 2) > maxH) s *= 2
        return s
    }

    private fun stackTraceString(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    // -------------------------------------------------------------------------
    // Build UI programmatically
    // -------------------------------------------------------------------------

    private fun buildUi() {
        val density = resources.displayMetrics.density

        fun dpToPx(dp: Int) = (dp * density + 0.5f).toInt()

        // Helper: full-width LayoutParams with vertical margins
        fun lp(topDp: Int = 8, bottomDp: Int = 8) =
            LinearLayout.LayoutParams(-1, -2).also {
                it.setMargins(0, dpToPx(topDp), 0, dpToPx(bottomDp))
            }

        // ---- TOP: title ----
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
        tvTitle = TextView(this).apply {
            text = "UFR-II v$ver"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        // ---- TOP: RadioGroup color mode ----
        rbColor = RadioButton(this).apply {
            text = "Barva (color)"
            id = 101
            isChecked = true
        }
        rbMono = RadioButton(this).apply {
            text = "Černobíle (mono)"
            id = 102
        }
        rgColorMode = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(rbColor)
            addView(rbMono)
            setOnCheckedChangeListener { _, checkedId ->
                selectedColorMode = if (checkedId == 101) ColorMode.COLOR else ColorMode.MONO
                Log.d(TAG, "[ui] colorMode=$selectedColorMode")
            }
        }

        // ---- TOP: TISK button — GONE until a file is shared ----
        btnTisk = Button(this).apply {
            text = "TISK"
            textSize = 16f
            isEnabled = false
            isClickable = true
            isFocusable = true
            minimumHeight = dpToPx(48)
            visibility = android.view.View.GONE
            setOnClickListener { doPrint() }
        }

        // ---- TOP: TEST TISK button — always visible ----
        btnTestTisk = Button(this).apply {
            text = "TEST TISK (vestavěné PDF)"
            textSize = 14f
            isEnabled = true
            isClickable = true
            isFocusable = true
            minimumHeight = dpToPx(48)
            setOnClickListener { doTestTisk() }
        }

        // ---- TOP: ProgressBar (indeterminate, hidden initially) ----
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = ProgressBar.GONE
        }

        // ---- MIDDLE: fixed-height log box ----
        tvLog = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(4))
            text = "Log:\n"
        }
        scrollLog = ScrollView(this).apply {
            setBackgroundColor(0xFFF0F0F0.toInt())
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            addView(tvLog, LinearLayout.LayoutParams(-1, -2))
        }

        // ---- BOTTOM: Kopírovat log ----
        btnCopyLog = Button(this).apply {
            text = "Kopírovat log"
            textSize = 13f
            isEnabled = true
            isClickable = true
            isFocusable = true
            minimumHeight = dpToPx(48)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("canonprint log", tvLog.text))
                Toast.makeText(this@MainActivity, "Log zkopírován!", Toast.LENGTH_SHORT).show()
            }
        }

        // ---- BOTTOM: Diagnostika ----
        btnDiag = Button(this).apply {
            text = "Diagnostika"
            textSize = 13f
            isEnabled = true
            isClickable = true
            isFocusable = true
            minimumHeight = dpToPx(48)
            setOnClickListener { doDiag() }
        }

        // ---- BOTTOM: PrintService group label ----
        val tvPrintService = TextView(this).apply {
            text = "Tiskárna v systému (PrintService):"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dpToPx(4), 0, 0)
        }

        // ---- BOTTOM: Povolit tiskárnu v Nastavení ----
        btnPovolit = Button(this).apply {
            text = "Povolit tiskárnu v Nastavení"
            textSize = 13f
            isEnabled = true
            isClickable = true
            isFocusable = true
            minimumHeight = dpToPx(48)
            setOnClickListener {
                log("Zapni CanonPrint v Nastavení → Tisk, pak lze tisknout z jakékoli aplikace přes Sdílet → Tisk.")
                try {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_PRINT_SETTINGS))
                } catch (e: Exception) {
                    try { startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
                }
            }
        }

        // ---- BOTTOM: Přidat / upravit tiskárnu ----
        btnPridatTiskarnu = Button(this).apply {
            text = "Přidat / upravit tiskárnu"
            textSize = 13f
            isEnabled = true
            isClickable = true
            isFocusable = true
            minimumHeight = dpToPx(48)
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, cz.ufrii.print.AddPrinterActivity::class.java))
            }
        }

        // ---- BOTTOM: Log tiskové služby ----
        btnSvcLog = Button(this).apply {
            text = "Log tiskové služby"
            textSize = 13f
            isEnabled = true
            isClickable = true
            isFocusable = true
            minimumHeight = dpToPx(48)
            setOnClickListener {
                val logFile = java.io.File(filesDir, "printservice.log")
                if (!logFile.exists() || logFile.length() == 0L) {
                    log("Log tiskové služby je prázdný (zatím neproběhla žádná tisková úloha přes systémový dialog).")
                } else {
                    log("\n--- Log tiskové služby (${logFile.absolutePath}) ---")
                    log(logFile.readText())
                    log("--- Konec logu tiskové služby ---")
                }
            }
        }

        // ---- BOTTOM: Smazat log tiskové služby ----
        btnSvcLogClear = Button(this).apply {
            text = "Smazat log tiskové služby"
            textSize = 13f
            isEnabled = true
            isClickable = true
            isFocusable = true
            minimumHeight = dpToPx(48)
            setOnClickListener {
                val logFile = java.io.File(filesDir, "printservice.log")
                if (logFile.exists()) {
                    logFile.delete()
                    log("Log tiskové služby smazán.")
                } else {
                    log("Log tiskové služby neexistuje (není co mazat).")
                }
            }
        }

        // ---- Root vertical LinearLayout (NOT a ScrollView) ----
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            fitsSystemWindows = true
            setPadding(dpToPx(16), dpToPx(16) + dpToPx(32), dpToPx(16), dpToPx(16))

            // Top section
            addView(tvTitle,     lp(topDp = 0, bottomDp = 8))
            addView(rgColorMode, lp(topDp = 0, bottomDp = 8))
            addView(btnTisk,     lp())
            addView(btnTestTisk, lp())
            addView(progressBar, LinearLayout.LayoutParams(-1, -2).also {
                it.setMargins(0, dpToPx(4), 0, dpToPx(4))
            })

            // Middle: fixed-height log box
            addView(scrollLog, LinearLayout.LayoutParams(-1, dpToPx(380)).also {
                it.setMargins(0, dpToPx(8), 0, dpToPx(8))
            })

            // Bottom section
            addView(btnCopyLog,        lp())
            addView(btnDiag,           lp())
            addView(tvPrintService,    lp(topDp = 8, bottomDp = 2))
            addView(btnPovolit,        lp(topDp = 2, bottomDp = 4))
            addView(btnPridatTiskarnu, lp(topDp = 4, bottomDp = 8))
            addView(btnSvcLog,         lp(topDp = 4, bottomDp = 4))
            addView(btnSvcLogClear,    lp(topDp = 0, bottomDp = 8))
        }

        setContentView(root)
    }
}
