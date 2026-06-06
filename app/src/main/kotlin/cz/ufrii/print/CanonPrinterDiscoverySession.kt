package cz.ufrii.print

import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log

private const val TAG = "CanonPrint"

/**
 * Manages the lifecycle of printer discovery for the Android Print framework.
 *
 * The framework calls [onStartPrinterDiscovery] when the user opens the print
 * dialog (or the system needs a fresh list) and [onStopPrinterDiscovery] when
 * it is done.  [onStartPrinterStateTracking] is called for each printer the
 * user actually selects — we attach full [PrinterCapabilitiesInfo] there so
 * the framework can offer the right options (media, resolution, colour mode).
 *
 * Internal state:
 *   [printers]  — live merged map keyed by printer id.  Manual printers from
 *                 [PrinterStore] seed it; [DiscoveryEngine] results are added
 *                 only when no MANUAL entry with the same ip:port already exists.
 *   [idMap]     — maps a framework [PrinterId]'s localId back to our [Printer]
 *                 so [CanonPrintService] can resolve a queued job's destination.
 */
class CanonPrinterDiscoverySession(
    private val service: PrintService
) : PrinterDiscoverySession() {

    // Ordered map so the UI list is stable
    private val printers = LinkedHashMap<String, Printer>()

    // PrinterId.localId → Printer (populated in publish())
    private val idMap = HashMap<String, Printer>()

    private val discoveryEngine = DiscoveryEngine(service)

    // -------------------------------------------------------------------------
    // Discovery lifecycle
    // -------------------------------------------------------------------------

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        Log.d(TAG, "[session] onStartPrinterDiscovery priorityList=${priorityList.size}")

        // Always start fresh
        synchronized(printers) { printers.clear() }
        synchronized(idMap) { idMap.clear() }

        // Seed with persisted manual printers (includes default Canon MF8030Cn)
        val manual = PrinterStore.loadManual(service)
        synchronized(printers) {
            for (p in manual) {
                printers[p.id] = p
            }
        }
        Log.d(TAG, "[session] seeded ${manual.size} manual printers")
        publish()

        // Start dynamic discovery — callback fires on main thread (DiscoveryEngine contract)
        discoveryEngine.start { discovered ->
            // Manual entries win: skip if an ip:port already covered by a MANUAL printer
            val ipPort = "${discovered.ip}:${discovered.port}"
            val alreadyCoveredByManual = synchronized(printers) {
                printers.values.any { it.source == PrinterSource.MANUAL && "${it.ip}:${it.port}" == ipPort }
            }
            if (!alreadyCoveredByManual) {
                val added = synchronized(printers) {
                    if (!printers.containsKey(discovered.id)) {
                        printers[discovered.id] = discovered
                        true
                    } else false
                }
                if (added) {
                    Log.d(TAG, "[session] discovered printer id=${discovered.id} name=${discovered.name}")
                    publish()
                }
            } else {
                Log.d(TAG, "[session] skip discovered $ipPort — already covered by MANUAL entry")
            }
        }
    }

    override fun onStopPrinterDiscovery() {
        Log.d(TAG, "[session] onStopPrinterDiscovery")
        discoveryEngine.stop()
    }

    // -------------------------------------------------------------------------
    // Printer state tracking — attach capabilities when a printer is selected
    // -------------------------------------------------------------------------

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        Log.d(TAG, "[session] onStartPrinterStateTracking localId=${printerId.localId}")
        val printer = printerFor(printerId) ?: run {
            Log.w(TAG, "[session] no Printer for localId=${printerId.localId}")
            return
        }
        attachCapabilities(printerId, printer)
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) {
        Log.d(TAG, "[session] onStopPrinterStateTracking localId=${printerId.localId}")
        // No-op: we don't poll the printer for live status
    }

    // -------------------------------------------------------------------------
    // Validate — re-publish current set (no network probing)
    // -------------------------------------------------------------------------

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
        Log.d(TAG, "[session] onValidatePrinters count=${printerIds.size}")
        publish()
    }

    // -------------------------------------------------------------------------
    // Destroy
    // -------------------------------------------------------------------------

    override fun onDestroy() {
        Log.d(TAG, "[session] onDestroy")
        discoveryEngine.stop()
        synchronized(printers) { printers.clear() }
        synchronized(idMap) { idMap.clear() }
    }

    // -------------------------------------------------------------------------
    // Public helper for CanonPrintService
    // -------------------------------------------------------------------------

    /**
     * Resolves a framework [PrinterId] back to our [Printer] model so
     * [CanonPrintService.onPrintJobQueued] can obtain the target ip/port.
     * Returns null if the printer is unknown (shouldn't happen in normal flow).
     */
    fun printerFor(printerId: PrinterId): Printer? {
        return synchronized(idMap) { idMap[printerId.localId] }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Converts the current [printers] map to a list of [PrinterInfo] objects
     * (without capabilities — bare IDLE status) and calls [addPrinters].
     * Also refreshes [idMap] so [printerFor] stays consistent.
     */
    private fun publish() {
        val infos = mutableListOf<PrinterInfo>()
        synchronized(printers) {
            synchronized(idMap) { idMap.clear() }
            for (p in printers.values) {
                val printerId = service.generatePrinterId(p.id)
                val info = PrinterInfo.Builder(printerId, p.name, PrinterInfo.STATUS_IDLE).build()
                infos.add(info)
                synchronized(idMap) { idMap[p.id] = p }
            }
        }
        Log.d(TAG, "[session] publish ${infos.size} printers")
        addPrinters(infos)
    }

    /**
     * Builds [PrinterCapabilitiesInfo] appropriate for the printer's colour
     * capability, then re-publishes that printer's [PrinterInfo] with the
     * capabilities attached.  The framework requires this before it will allow
     * the user to confirm a print job.
     */
    private fun attachCapabilities(printerId: PrinterId, printer: Printer) {
        val colorModes = if (printer.colorCapable) {
            PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME
        } else {
            PrintAttributes.COLOR_MODE_MONOCHROME
        }
        val defaultColorMode = if (printer.colorCapable) {
            PrintAttributes.COLOR_MODE_COLOR
        } else {
            PrintAttributes.COLOR_MODE_MONOCHROME
        }

        val caps = PrinterCapabilitiesInfo.Builder(printerId)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, /* isDefault= */ true)
            .addResolution(
                PrintAttributes.Resolution("600dpi", "600 dpi", 600, 600),
                /* isDefault= */ true
            )
            .setColorModes(colorModes, defaultColorMode)
            // ~5 mm margins at 600 dpi: 5mm/25.4mm * 600dpi ≈ 118 thousandths-of-inch
            .setMinMargins(PrintAttributes.Margins(118, 118, 118, 118))
            .build()

        val infoWithCaps = PrinterInfo.Builder(printerId, printer.name, PrinterInfo.STATUS_IDLE)
            .setCapabilities(caps)
            .build()

        Log.d(TAG, "[session] attachCapabilities id=${printer.id} colorCapable=${printer.colorCapable}")
        addPrinters(listOf(infoWithCaps))
    }
}
