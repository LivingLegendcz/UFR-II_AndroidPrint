package cz.ufrii.print.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.ufrii.print.AppLog
import cz.ufrii.print.AppSettings
import cz.ufrii.print.ColorMode
import cz.ufrii.print.DiscoveryEngine
import cz.ufrii.print.JobResult
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
import kotlinx.coroutines.launch

/** A file handed to the app (share / VIEW / picker) waiting to be printed. */
data class PendingFile(val uri: Uri, val isImage: Boolean, val name: String)

sealed class JobState {
    object Idle : JobState()
    data class Running(val title: String) : JobState()
    data class Done(val title: String, val result: JobResult) : JobState()
}

/** Running diagnostics: current step (0-based) of [total]. */
data class DiagState(val step: Int, val total: Int, val label: String)

/** A shared/picked file the app cannot render itself (docx, xlsx, odt …). */
data class UnsupportedFile(val uri: Uri, val name: String, val mime: String?)

/** Result of "Otestovat spojení" on the add-printer screen. */
sealed class ProbeState {
    object Idle : ProbeState()
    object Testing : ProbeState()
    data class Reachable(val caps: PrinterCapabilities) : ProbeState()
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

    fun defaultPrinter(): Printer? = printer(_defaultId.value) ?: _printers.value.firstOrNull()

    fun setDefault(id: String) {
        PrinterStore.setDefault(ctx, id)
        reloadPrinters()
        refreshStatus(id)
    }

    fun savePrinter(p: Printer) {
        PrinterStore.update(ctx, p)
        if (_printers.value.isEmpty()) PrinterStore.setDefault(ctx, p.id)
        reloadPrinters()
        refreshStatus(p.id)
    }

    fun removePrinter(id: String) {
        PrinterStore.remove(ctx, id)
        _status.value = _status.value - id
        reloadPrinters()
    }

    /** SNMP status read: on Home open, after each print, on pull from the detail screen. */
    fun refreshStatus(id: String? = _defaultId.value) {
        val p = printer(id) ?: return
        if (p.id in _statusLoading.value) return
        _statusLoading.value = _statusLoading.value + p.id
        viewModelScope.launch {
            try {
                val s = PrinterProbe.readStatus(p.ip)
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
            val caps = try { PrinterProbe.probeCapabilities(p.ip) } finally {
                _redetecting.value = _redetecting.value - id
            }
            AppLog.i("Schopnosti ${p.ip}: ${caps.model}, barva=${caps.color}, duplex=${caps.duplex}, skener=${caps.scanner}")
            val updated = p.copy(
                capabilities = caps.copy(adf = caps.adf ?: p.capabilities?.adf),
                colorCapable = caps.color ?: p.colorCapable,
                model = caps.model ?: p.model
            )
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

    fun testConnection(ip: String, port: Int) {
        _probe.value = ProbeState.Testing
        viewModelScope.launch {
            val tcp = PrinterProbe.tcpReachable(ip, port)
            if (!tcp) {
                AppLog.err("Test spojení $ip:$port — neodpovídá")
                _probe.value = ProbeState.Unreachable
                return@launch
            }
            val caps = PrinterProbe.probeCapabilities(ip)
            AppLog.ok("Test spojení $ip:$port — OK, ${caps.model ?: "model neznámý"}")
            _probe.value = ProbeState.Reachable(caps)
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
            val saved = _printers.value.any { it.ip == p.ip && it.port == p.port }
            if (!saved) _found.value = _found.value + p
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
        AppLog.i("Soubor k tisku: $name ($type)")
    }

    fun clearUnsupported() { _unsupported.value = null }

    fun clearPending() { _pending.value = null }

    fun dismissJob() { if (_job.value is JobState.Done) _job.value = JobState.Idle }

    fun printPending(mode: ColorMode) {
        val f = _pending.value ?: return
        val p = defaultPrinter() ?: return
        if (_job.value is JobState.Running) return
        _job.value = JobState.Running(f.name)
        viewModelScope.launch {
            val r = PrintJobs(ctx).printFile(f.uri, f.isImage, effective(mode, p), p)
            _job.value = JobState.Done(f.name, r)
            if (r.ok) _pending.value = null
            refreshStatus(p.id)
        }
    }

    fun printTestPage(mode: ColorMode) {
        val p = defaultPrinter() ?: return
        if (_job.value is JobState.Running) return
        val title = "sample.pdf"
        _job.value = JobState.Running(title)
        viewModelScope.launch {
            val r = PrintJobs(ctx).printTestPage(effective(mode, p), p)
            _job.value = JobState.Done(title, r)
            refreshStatus(p.id)
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
        super.onCleared()
    }

    companion object {
        /** New printer from the add form (manual entry). */
        fun newPrinter(name: String, ip: String, port: Int, caps: PrinterCapabilities?, id: String? = null) =
            Printer(
                id = id ?: PrinterStore.newManualId(),
                name = name,
                ip = ip,
                port = port,
                colorCapable = caps?.color ?: true,
                source = PrinterSource.MANUAL,
                model = caps?.model ?: "Canon",
                capabilities = caps
            )
    }
}
