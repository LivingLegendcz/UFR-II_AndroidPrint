package cz.ufrii.print.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.ufrii.print.AppLog
import cz.ufrii.print.AppSettings
import cz.ufrii.print.HistoryEntry
import cz.ufrii.print.JobHistory
import cz.ufrii.print.ColorMode
import cz.ufrii.print.DiscoveryEngine
import cz.ufrii.print.DuplexCalibration
import cz.ufrii.print.DuplexCalibrations
import cz.ufrii.print.JobResult
import cz.ufrii.print.PaperSource
import cz.ufrii.print.PrintOptions
import cz.ufrii.print.ScanError
import cz.ufrii.print.ScanJobs
import cz.ufrii.print.ScanResult
import cz.ufrii.print.ScanSettings
import cz.ufrii.print.ScanSource
import cz.ufrii.print.PrintJobs
import cz.ufrii.print.Printer
import cz.ufrii.print.PrinterSource
import cz.ufrii.print.PrinterStore
import cz.ufrii.print.status.PrinterCapabilities
import cz.ufrii.print.status.PrinterProbe
import cz.ufrii.print.status.PrinterStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file handed to the app (share / VIEW / picker) waiting to be printed. */
data class PendingFile(val uri: Uri, val isImage: Boolean, val name: String)

sealed class JobState {
    object Idle : JobState()
    /** [pass] "1/2" / "2/2" for manual duplex; [page] 0-based of [total] (0 = unknown). */
    data class Running(val title: String, val pass: String? = null, val page: Int = 0, val total: Int = 0) : JobState()
    data class Done(val title: String, val result: JobResult) : JobState()
}

/** Running diagnostics: current step (0-based) of [total]. */
data class DiagState(val step: Int, val total: Int, val label: String)

/** A shared/picked file the app cannot render itself (docx, xlsx, odt …). */
data class UnsupportedFile(val uri: Uri, val name: String, val mime: String?)

/** Duplex calibration wizard: printing side [step], or waiting for the user's answers. */
sealed class CalibState {
    object Idle : CalibState()
    data class Printing(val step: Int) : CalibState()
    data class Printed(val step: Int) : CalibState()
    data class Failed(val step: Int, val message: String) : CalibState()
    /** Between numbers: the sheet goes back into the tray. */
    object Reinsert : CalibState()
}

sealed class ScanState {
    object Idle : ScanState()
    data class Scanning(val page: Int, val percent: Int) : ScanState()
    data class Done(val pages: List<java.io.File>, val settings: ScanSettings) : ScanState()
    data class Failed(val error: ScanError, val message: String) : ScanState()
}

/** Low-resolution scan of the whole glass for choosing the scan area. */
sealed class PreviewState {
    object Loading : PreviewState()
    /** [suggested] = content found on the glass as (left, top, right, bottom) fractions. */
    data class Ready(val bitmap: android.graphics.Bitmap, val suggested: FloatArray?) : PreviewState()
    data class Failed(val error: ScanError, val message: String) : PreviewState()
}

/** Result of "Otestovat spojení" on the add-printer screen. */
sealed class ProbeState {
    object Idle : ProbeState()
    object Testing : ProbeState()
    /** [scan]: what the scanner offers (also tells the ADF); [ipv6]: the printer's IPv6 from mDNS. */
    data class Reachable(val caps: PrinterCapabilities, val scan: cz.ufrii.print.ScanCapabilities? = null,
                         val ipv6: String? = null) : ProbeState()
    object Unreachable : ProbeState()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()

    // -------------------------------------------------------------------------
    // Printers
    // -------------------------------------------------------------------------

    private val _printers = MutableStateFlow<List<Printer>>(emptyList())
    val printers: StateFlow<List<Printer>> = _printers

    private val _defaultId = MutableStateFlow<String?>(null)
    val defaultId: StateFlow<String?> = _defaultId

    /** Last SNMP status per printer id (missing = not read yet). */
    private val _status = MutableStateFlow<Map<String, PrinterStatus>>(emptyMap())
    val status: StateFlow<Map<String, PrinterStatus>> = _status

    private val _statusLoading = MutableStateFlow<Set<String>>(emptySet())
    val statusLoading: StateFlow<Set<String>> = _statusLoading

    init {
        AppSettings.load(ctx)
        reloadPrinters()
    }

    fun reloadPrinters() {
        _printers.value = PrinterStore.loadManual(ctx)
        _defaultId.value = PrinterStore.defaultPrinter(ctx)?.id
    }

    fun printer(id: String?): Printer? = _printers.value.firstOrNull { it.id == id }

    /** A shared setup link opened from outside (QR in the camera app, messenger); UfrApp shows the import. */
    private val _importLink = MutableStateFlow<String?>(null)
    val importLink: StateFlow<String?> = _importLink
    fun setImportLink(link: String) { _importLink.value = link }
    fun consumeImportLink() { _importLink.value = null }

    fun defaultPrinter(): Printer? = printer(_defaultId.value) ?: _printers.value.firstOrNull()

    fun setDefault(id: String) {
        PrinterStore.setDefault(ctx, id)
        reloadPrinters()
        refreshStatus(id)
    }

    /**
     * Asks the scanner what it offers: a listed document feeder means the device has an ADF
     * (pixma reports it from its model table), so no test scan is needed to know.
     */
    private suspend fun withScanner(ip: String, caps: PrinterCapabilities): Pair<PrinterCapabilities, cz.ufrii.print.ScanCapabilities?> {
        if (caps.scanner == false) return caps.copy(adf = false) to null
        val scan = ScanJobs(ctx).queryOptions(ip) ?: return caps to null
        return caps.copy(scanner = true, adf = ScanSource.FEEDER in scan.sources) to scan
    }

    private suspend fun findIpv6(ipv4: String): String? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { DiscoveryEngine(ctx).findIpv6(ipv4) }.getOrNull()
    }

    fun savePrinter(printer: Printer) {
        // The edit form does not carry the driver: keep a hand-picked one.
        val old = printer(printer.id)
        val p = if (old?.ppdManual == true) printer.copy(ppd = old.ppd, ppdManual = true) else withPpd(printer)
        PrinterStore.update(ctx, p)
        cz.ufrii.print.PrinterAddress.invalidate(p.id)
        // Scanner options from "Otestovat spojení" belong to this printer now.
        (_probe.value as? ProbeState.Reachable)?.scan?.let {
            cz.ufrii.print.ScanCapabilitiesStore.set(ctx, p.id, it)
            _scanCaps.value = _scanCaps.value + (p.id to it)
        }
        if (_printers.value.isEmpty()) PrinterStore.setDefault(ctx, p.id)
        reloadPrinters()
        refreshStatus(p.id)
    }

    fun removePrinter(id: String) {
        PrinterStore.remove(ctx, id)
        _status.value = _status.value - id
        reloadPrinters()
    }

    /**
     * Fills in the driver description (PPD) from what the printer reports (MFNP model, SNMP
     * description), unless the user picked one. Unknown model → keeps the current / default one.
     */
    private fun withPpd(p: Printer, rematch: Boolean = false): Printer {
        if (p.ppd != null && !rematch) return p
        val caps = p.capabilities ?: return p
        val hit = cz.ufrii.print.PpdCatalog.match(ctx, listOf(caps.mdl, caps.model, caps.sysDescr, p.name), caps.color ?: p.colorCapable)
            ?: return p
        if (hit.file != p.ppd) AppLog.ok("Ovladač pro ${p.name}: ${hit.short} (${hit.file})")
        return p.copy(ppd = hit.file)
    }

    /** User's choice from the model list (Tiskárna → Ovladač). */
    fun setPpd(id: String, file: String) {
        val p = printer(id) ?: return
        PrinterStore.update(ctx, p.copy(ppd = file, ppdManual = true))
        reloadPrinters()
    }

    /** [p] on the address that answers now (IPv4, else its IPv6) — see [cz.ufrii.print.PrinterAddress]. */
    private suspend fun live(p: Printer): Printer =
        withContext(kotlinx.coroutines.Dispatchers.IO) { cz.ufrii.print.PrinterAddress.resolve(ctx, p) }

    /** SNMP status read: on Home open, after each print, on pull from the detail screen. */
    fun refreshStatus(id: String? = _defaultId.value) {
        val p = printer(id) ?: return
        if (p.id in _statusLoading.value) return
        _statusLoading.value = _statusLoading.value + p.id
        viewModelScope.launch {
            try {
                val s = PrinterProbe.readStatus(live(p).ip)
                _status.value = _status.value + (p.id to s)
            } catch (e: Exception) {
                AppLog.err("Stav tiskárny ${p.ip}: ${e.message}")
            } finally {
                _statusLoading.value = _statusLoading.value - p.id
            }
        }
    }

    /** Re-run capability detection for a saved printer ("Znovu zjistit"). */
    private val _redetecting = MutableStateFlow<Set<String>>(emptySet())
    val redetecting: StateFlow<Set<String>> = _redetecting

    fun redetect(id: String) {
        val p = printer(id) ?: return
        if (id in _redetecting.value) return
        _redetecting.value = _redetecting.value + id
        viewModelScope.launch {
            val ip = live(p).ip
            val (caps, scan) = try { withScanner(ip, PrinterProbe.probeCapabilities(ip)) } finally {
                _redetecting.value = _redetecting.value - id
            }
            scan?.let {
                cz.ufrii.print.ScanCapabilitiesStore.set(ctx, id, it)
                _scanCaps.value = _scanCaps.value + (id to it)
            }
            val ipv6 = p.ipv6 ?: if (cz.ufrii.print.PrinterAddress.isIpv6(p.ip)) null else findIpv6(p.ip)
            AppLog.i("Schopnosti ${p.ip}: ${caps.model}, barva=${caps.color}, duplex=${caps.duplex}, skener=${caps.scanner}")
            val updated = withPpd(p.copy(
                capabilities = caps.copy(adf = caps.adf ?: p.capabilities?.adf),
                colorCapable = caps.color ?: p.colorCapable,
                model = caps.model ?: p.model,
                ipv6 = ipv6
            ), rematch = !p.ppdManual)
            PrinterStore.update(ctx, updated)
            reloadPrinters()
            refreshStatus(id)
        }
    }

    // -------------------------------------------------------------------------
    // Add printer: connection test + capability probe
    // -------------------------------------------------------------------------

    private val _probe = MutableStateFlow<ProbeState>(ProbeState.Idle)
    val probe: StateFlow<ProbeState> = _probe

    fun resetProbe() { _probe.value = ProbeState.Idle }

    fun testConnection(address: String, port: Int) {
        _probe.value = ProbeState.Testing
        val ip = cz.ufrii.print.PrinterAddress.withZone(ctx, address)
        viewModelScope.launch {
            val tcp = PrinterProbe.tcpReachable(ip, port)
            if (!tcp) {
                AppLog.err("Test spojení $ip:$port — neodpovídá")
                _probe.value = ProbeState.Unreachable
                return@launch
            }
            // The IPv6 lookup (mDNS, up to 6 s) runs while SNMP and the scanner are asked.
            val ipv6Job = async {
                if (cz.ufrii.print.PrinterAddress.isIpv6(ip)) null else findIpv6(ip)
            }
            val (caps, scan) = withScanner(ip, PrinterProbe.probeCapabilities(ip))
            val ipv6 = ipv6Job.await()
            AppLog.ok("Test spojení $ip:$port — OK, ${caps.model ?: "model neznámý"}" + (ipv6?.let { ", IPv6 $it" } ?: ""))
            _probe.value = ProbeState.Reachable(caps, scan, ipv6)
        }
    }

    // -------------------------------------------------------------------------
    // Network discovery (Printers screen)
    // -------------------------------------------------------------------------

    private val _found = MutableStateFlow<List<Printer>>(emptyList())
    val found: StateFlow<List<Printer>> = _found

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering

    private var engine: DiscoveryEngine? = null
    private var discoveryTimeout: Job? = null

    fun startDiscovery() {
        if (engine != null) return
        _found.value = emptyList()
        _discovering.value = true
        val e = DiscoveryEngine(ctx)
        engine = e
        e.start { p ->
            val saved = _printers.value.any { (it.ip == p.ip || it.ip == p.ipv6) && it.port == p.port }
            // Same id again = the real (mDNS) name for an address found by the subnet scan.
            if (!saved) _found.value = _found.value.filter { it.id != p.id } + p
        }
        discoveryTimeout = viewModelScope.launch {
            kotlinx.coroutines.delay(20_000)
            stopDiscovery()
        }
    }

    fun stopDiscovery() {
        engine?.stop()
        engine = null
        discoveryTimeout?.cancel()
        _discovering.value = false
    }

    // -------------------------------------------------------------------------
    // Print jobs (in-app path; the system dialog goes through CanonPrintService)
    // -------------------------------------------------------------------------

    private val _pending = MutableStateFlow<PendingFile?>(null)
    val pending: StateFlow<PendingFile?> = _pending

    private val _job = MutableStateFlow<JobState>(JobState.Idle)
    val job: StateFlow<JobState> = _job

    private val _unsupported = MutableStateFlow<UnsupportedFile?>(null)
    val unsupported: StateFlow<UnsupportedFile?> = _unsupported

    fun setPending(uri: Uri, mime: String?) {
        val type = mime ?: ctx.contentResolver.getType(uri)
        val name = displayName(uri)
        val pdf = type == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)
        val image = type?.startsWith("image/") == true
        if (!pdf && !image) {
            // Office & other formats: the app has no renderer; the owning app prints via the PrintService.
            _unsupported.value = UnsupportedFile(uri, name, type)
            _pending.value = null
            AppLog.i("Soubor $name ($type) nelze tisknout přímo — nabízím otevření v jiné aplikaci")
            return
        }
        _unsupported.value = null
        _pending.value = PendingFile(uri, image, name)
        _pendingPages.value = null
        if (pdf) viewModelScope.launch { _pendingPages.value = PrintJobs(ctx).pdfPageCount(uri) }
        AppLog.i("Soubor k tisku: $name ($type)")
    }

    fun clearUnsupported() { _unsupported.value = null }

    fun clearPending() { _pending.value = null; _pendingPages.value = null }

    fun dismissJob() { if (_job.value is JobState.Done) _job.value = JobState.Idle }

    /** Page count of the pending PDF (null = image / unknown). */
    private val _pendingPages = MutableStateFlow<Int?>(null)
    val pendingPages: StateFlow<Int?> = _pendingPages

    fun printPending(opts: PrintOptions) {
        val f = _pending.value ?: return
        val p = defaultPrinter() ?: return
        if (_job.value is JobState.Running) return
        _job.value = JobState.Running(f.name)
        viewModelScope.launch {
            val o = opts.copy(colorMode = effective(opts.colorMode, p))
            val r = PrintJobs(ctx).printFile(f.uri, f.isImage, p, o, f.name) { pass, page, total ->
                _job.value = JobState.Running(f.name, pass, page, total)
            }
            _job.value = JobState.Done(f.name, r)
            if (r.ok) { _pending.value = null; _pendingPages.value = null }
            refreshStatus(p.id)
        }
    }

    // -------------------------------------------------------------------------
    // Manual duplex calibration (one sheet, per printer × tray)
    // -------------------------------------------------------------------------

    private val _calib = MutableStateFlow<CalibState>(CalibState.Idle)
    val calib: StateFlow<CalibState> = _calib

    fun resetCalibration() { _calib.value = CalibState.Idle }

    /** The answers asked for one more number on the same sheet: show the re-insert step again. */
    fun markCalibrationReinsert() { _calib.value = CalibState.Reinsert }

    fun printCalibration(printerId: String, source: PaperSource, step: Int, rotate180: Boolean = false) {
        val p = printer(printerId) ?: return
        if (_calib.value is CalibState.Printing || _job.value is JobState.Running) return
        _calib.value = CalibState.Printing(step)
        viewModelScope.launch {
            val r = PrintJobs(ctx).printCalibration(p, source, step, rotate180)
            _calib.value = if (r.ok) CalibState.Printed(step) else CalibState.Failed(step, r.message)
        }
    }

    fun saveCalibration(printerId: String, source: PaperSource, cal: DuplexCalibration) {
        DuplexCalibrations.set(ctx, printerId, source, cal)
        AppLog.ok("Kalibrace uložena: ${source.ppd} lícem ${if (cal.faceUp) "nahoru" else "dolů"}, otočit=${cal.rotate180}")
        _calib.value = CalibState.Idle
    }

    // -------------------------------------------------------------------------
    // Scanning
    // -------------------------------------------------------------------------

    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = _scan

    /** Scanner options per printer id, read from the scanner itself (`scanimage -A`). */
    private val _scanCaps = MutableStateFlow<Map<String, cz.ufrii.print.ScanCapabilities>>(emptyMap())
    val scanCaps: StateFlow<Map<String, cz.ufrii.print.ScanCapabilities>> = _scanCaps
    private val _scanCapsLoading = MutableStateFlow(false)
    val scanCapsLoading: StateFlow<Boolean> = _scanCapsLoading
    private val _scanCapsFailed = MutableStateFlow(false)
    val scanCapsFailed: StateFlow<Boolean> = _scanCapsFailed

    /** Cached value first; asks the scanner when nothing is cached or [force]. */
    fun loadScanCaps(force: Boolean = false) {
        val p = defaultPrinter() ?: return
        if (!force) {
            (_scanCaps.value[p.id] ?: cz.ufrii.print.ScanCapabilitiesStore.get(ctx, p.id))?.let {
                _scanCaps.value = _scanCaps.value + (p.id to it); return
            }
        }
        if (_scanCapsLoading.value || _scan.value is ScanState.Scanning) return
        _scanCapsLoading.value = true
        _scanCapsFailed.value = false
        viewModelScope.launch {
            val caps = ScanJobs(ctx).queryOptions(live(p).ip)
            if (caps != null) {
                cz.ufrii.print.ScanCapabilitiesStore.set(ctx, p.id, caps)
                _scanCaps.value = _scanCaps.value + (p.id to caps)
            } else _scanCapsFailed.value = true
            _scanCapsLoading.value = false
        }
    }

    fun resetScan() {
        if (_scan.value is ScanState.Scanning) return
        _scan.value = ScanState.Idle
        clearPreview()
        sweepScans()
    }

    private val _preview = MutableStateFlow<PreviewState?>(null)
    val preview: StateFlow<PreviewState?> = _preview

    /** Whole glass at the lowest resolution (a few seconds), colour when the scanner has it. */
    fun startPreview() {
        val p = defaultPrinter() ?: return
        val caps = _scanCaps.value[p.id] ?: return
        if (_preview.value is PreviewState.Loading || _scan.value is ScanState.Scanning || _job.value is JobState.Running) return
        _preview.value = PreviewState.Loading
        viewModelScope.launch {
            val mode = if (ScanSource.GLASS in caps.sources && cz.ufrii.print.ScanMode.COLOR in caps.modes)
                cz.ufrii.print.ScanMode.COLOR else caps.modes.first()
            val (bmp, r) = ScanJobs(ctx).preview(live(p).ip, caps.device, mode, caps.resolutions.first())
            _preview.value = when {
                bmp != null -> PreviewState.Ready(bmp, withContext(kotlinx.coroutines.Dispatchers.Default) {
                    ScanJobs.contentBounds(bmp, caps.glassW ?: 215.9f, caps.glassH ?: 297f)
                })
                r is ScanResult.Failed -> PreviewState.Failed(r.error, r.message)
                else -> PreviewState.Failed(ScanError.OTHER, "náhled nelze načíst")
            }
        }
    }

    fun clearPreview() {
        if (_preview.value is PreviewState.Loading) return
        _preview.value = null
    }

    /** Scan pages are huge (up to ~100 MB each): drop them as soon as the result is closed. */
    private fun sweepScans() {
        // A scan handed to the print flow ("copy") stays until it is printed or dropped.
        val keep = _pending.value?.uri?.takeIf { it.authority == "${ctx.packageName}.files" }?.lastPathSegment
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { ScanJobs.sweep(ctx, SCAN_OUTPUT_TTL_MS, keep) }
    }

    fun startScan(settings: ScanSettings) {
        val p = defaultPrinter() ?: return
        if (_scan.value is ScanState.Scanning || _job.value is JobState.Running ||
            _preview.value is PreviewState.Loading) return
        _scan.value = ScanState.Scanning(1, 0)
        viewModelScope.launch {
            val device = _scanCaps.value[p.id]?.device
            val r = ScanJobs(ctx).scan(live(p).ip, device, settings) { page, pct -> _scan.value = ScanState.Scanning(page, pct) }
            _scan.value = when (r) {
                is ScanResult.Ok -> {
                    // A feeder scan that produced pages proves the ADF exists (no model catalogue).
                    if (settings.source == ScanSource.FEEDER && p.capabilities?.adf != true) markAdf(p, true)
                    JobHistory.add(ctx, HistoryEntry(System.currentTimeMillis(), "Sken · ${r.pages.size} str.", p.name,
                        true, "", HistoryEntry.VIA_SCAN))
                    ScanState.Done(r.pages, settings)
                }
                is ScanResult.Failed -> ScanState.Failed(r.error, r.message)
            }
        }
    }

    private fun markAdf(p: Printer, adf: Boolean) {
        val caps = p.capabilities ?: cz.ufrii.print.status.PrinterCapabilities(
            sysDescr = null, model = p.model, color = p.colorCapable, inputs = emptyList(), duplex = null,
            scanner = true, adf = null, snmp = false, detectedAt = System.currentTimeMillis())
        PrinterStore.update(ctx, p.copy(capabilities = caps.copy(adf = adf)))
        reloadPrinters()
    }

    /** Hands the finished scan to the print flow as a pending PDF ("copy"). */
    fun printScanAsCopy(pdf: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", pdf)
        setPending(uri, "application/pdf")
    }

    /** "Zrušit" on a running in-app job: kills the filter and the printer connection. */
    fun cancelJob() {
        if (_job.value is JobState.Running) PrintJobs.cancelActive()
    }

    fun printTestPage(mode: ColorMode, printerId: String? = null) {
        val p = printerId?.let { printer(it) } ?: defaultPrinter() ?: return
        if (_job.value is JobState.Running) return
        val title = "sample.pdf"
        _job.value = JobState.Running(title)
        viewModelScope.launch {
            val r = PrintJobs(ctx).printTestPage(effective(mode, p), p)
            _job.value = JobState.Done(title, r)
            refreshStatus(p.id)
        }
    }

    /** Scanner test from the printer detail: null = not run, else running / result. */
    sealed class ScannerTest {
        object Running : ScannerTest()
        data class Ok(val caps: cz.ufrii.print.ScanCapabilities) : ScannerTest()
        object Failed : ScannerTest()
    }
    private val _scannerTest = MutableStateFlow<Map<String, ScannerTest>>(emptyMap())
    val scannerTest: StateFlow<Map<String, ScannerTest>> = _scannerTest

    /**
     * Asks the scanner what it offers (`scanimage -L` + `-A`): proves it answers over the network
     * without scanning, and stores the options and the ADF result for the printer.
     */
    fun testScanner(id: String) {
        val p = printer(id) ?: return
        if (_scannerTest.value[id] == ScannerTest.Running || _scan.value is ScanState.Scanning) return
        _scannerTest.value = _scannerTest.value + (id to ScannerTest.Running)
        viewModelScope.launch {
            val scan = ScanJobs(ctx).queryOptions(live(p).ip)
            if (scan != null) {
                cz.ufrii.print.ScanCapabilitiesStore.set(ctx, id, scan)
                _scanCaps.value = _scanCaps.value + (id to scan)
                p.capabilities?.let { caps ->
                    PrinterStore.update(ctx, p.copy(capabilities = caps.copy(scanner = true, adf = ScanSource.FEEDER in scan.sources)))
                    reloadPrinters()
                }
            }
            _scannerTest.value = _scannerTest.value + (id to (scan?.let { ScannerTest.Ok(it) } ?: ScannerTest.Failed))
        }
    }

    private val _diag = MutableStateFlow<DiagState?>(null)
    val diag: StateFlow<DiagState?> = _diag

    fun runDiagnostics() {
        if (_job.value is JobState.Running || _diag.value != null) return
        _diag.value = DiagState(0, 5, "…")
        viewModelScope.launch {
            AppLog.acc("=== Diagnostika ===")
            try {
                PrintJobs(ctx).diagnostics { i, n, label -> _diag.value = DiagState(i, n, label) }
                AppLog.ok("=== Diagnostika hotova ===")
            } finally {
                _diag.value = null
            }
        }
    }

    /** Downgrade COLOR → MONO when the printer is mono-only. */
    private fun effective(mode: ColorMode, p: Printer): ColorMode {
        val color = p.capabilities?.color ?: p.colorCapable
        return if (mode == ColorMode.COLOR && !color) ColorMode.MONO else mode
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "soubor"

    override fun onCleared() {
        stopDiscovery()
        if (_scan.value !is ScanState.Scanning) Thread { ScanJobs.sweep(ctx, SCAN_OUTPUT_TTL_MS) }.start()
        super.onCleared()
    }

    companion object {
        /** How long finished scan files stay in cache (a share target may still read them). */
        private const val SCAN_OUTPUT_TTL_MS = 10 * 60_000L

        /** New printer from the add form (manual entry). */
        fun newPrinter(name: String, ip: String, port: Int, caps: PrinterCapabilities?, id: String? = null, ipv6: String? = null) =
            Printer(
                id = id ?: PrinterStore.newManualId(),
                name = name,
                ip = ip,
                port = port,
                colorCapable = caps?.color ?: true,
                source = PrinterSource.MANUAL,
                model = caps?.model ?: "Canon",
                capabilities = caps,
                ipv6 = ipv6
            )
    }
}
