package cz.ufrii.print

import android.content.Context
import cz.ufrii.print.status.PrinterCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ---------------------------------------------------------------------------
// Model
// ---------------------------------------------------------------------------

/** Where the printer entry originated. */
enum class PrinterSource { MANUAL, DISCOVERED }

/** Immutable descriptor for a single Canon network printer. */
data class Printer(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int = 9100,
    val colorCapable: Boolean = true,
    val source: PrinterSource = PrinterSource.MANUAL,
    val model: String = "Canon",
    /** D1: what the device can do (SNMP + MFNP probe), null until "Otestovat spojení" ran. */
    val capabilities: PrinterCapabilities? = null,
    /** The same machine's IPv6 (no zone): used when [ip] does not answer — see [PrinterAddress]. */
    val ipv6: String? = null,
    /** Driver description (PPD file in assets/ppd.zip); null = MF8000C, see [PpdCatalog]. */
    val ppd: String? = null,
    /** [ppd] was picked by the user — re-detection must not replace it. */
    val ppdManual: Boolean = false
)

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------

/**
 * Thin persistence layer for manually-added printers.
 * Storage: SharedPreferences "canon_printers", key "manual_printers" → JSON array.
 * Uses only platform org.json — no extra deps.
 */
object PrinterStore {

    const val DEFAULT_PORT = 9100

    private const val PREFS_NAME = "canon_printers"
    private const val KEY_LIST   = "manual_printers"
    private const val KEY_DEFAULT = "default_printer_id"

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Generate a new unique printer id. */
    fun newManualId(): String = UUID.randomUUID().toString()

    /**
     * Load persisted manual printers. A fresh install has none: the welcome screen
     * (or the system dialog's discovery) finds the printer on the user's own network.
     */
    fun loadManual(ctx: Context): List<Printer> = parseJson(prefs(ctx).getString(KEY_LIST, null))

    /** Serialize [list] and commit to SharedPreferences. */
    fun saveManual(ctx: Context, list: List<Printer>) {
        val json = serializeJson(list)
        prefs(ctx).edit().putString(KEY_LIST, json).apply()
    }

    /** The printer used by the in-app print paths: the chosen default, else the first one. */
    fun defaultPrinter(ctx: Context): Printer? {
        val list = loadManual(ctx)
        val id = prefs(ctx).getString(KEY_DEFAULT, null)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun setDefault(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_DEFAULT, id).apply()
    }

    /** Append [p] to the persisted list. */
    fun add(ctx: Context, p: Printer) {
        val current = loadManual(ctx).toMutableList()
        current.add(p)
        saveManual(ctx, current)
    }

    /** Remove the printer with the given [id] from the persisted list. */
    fun remove(ctx: Context, id: String) {
        val current = loadManual(ctx).filter { it.id != id }
        saveManual(ctx, current)
    }

    /**
     * Replace the entry whose id matches [p].id with [p].
     * If no match is found, [p] is appended.
     */
    fun update(ctx: Context, p: Printer) {
        val current = loadManual(ctx).toMutableList()
        val idx = current.indexOfFirst { it.id == p.id }
        if (idx >= 0) current[idx] = p else current.add(p)
        saveManual(ctx, current)
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Serialize a list of Printers to a JSON array string. */
    private fun serializeJson(list: List<Printer>): String {
        val arr = JSONArray()
        for (p in list) {
            val obj = JSONObject()
            obj.put("id",           p.id)
            obj.put("name",         p.name)
            obj.put("ip",           p.ip)
            obj.put("port",         p.port)
            obj.put("colorCapable", p.colorCapable)
            obj.put("source",       p.source.name)
            obj.put("model",        p.model)
            p.ipv6?.let { obj.put("ipv6", it) }
            p.ppd?.let { obj.put("ppd", it) }
            if (p.ppdManual) obj.put("ppdManual", true)
            p.capabilities?.let { obj.put("capabilities", it.toJson()) }
            arr.put(obj)
        }
        return arr.toString()
    }

    /**
     * Parse a JSON array string back into a list of Printers.
     * Malformed entries are silently skipped; missing fields fall back to defaults.
     */
    private fun parseJson(raw: String?): List<Printer> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = mutableListOf<Printer>()
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val id   = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val name = obj.optString("name", "Unknown Printer")
                    val ip   = obj.optString("ip").takeIf { it.isNotBlank() } ?: continue
                    val port         = obj.optInt("port", DEFAULT_PORT)
                    val colorCapable = obj.optBoolean("colorCapable", true)
                    val source       = try {
                        PrinterSource.valueOf(obj.optString("source", PrinterSource.MANUAL.name))
                    } catch (_: IllegalArgumentException) {
                        PrinterSource.MANUAL
                    }
                    val model = obj.optString("model", "Canon")
                    val caps = obj.optJSONObject("capabilities")?.let { PrinterCapabilities.fromJson(it) }
                    val ipv6 = obj.optString("ipv6").takeIf { it.isNotBlank() }
                    val ppd = obj.optString("ppd").takeIf { it.isNotBlank() }
                    result.add(Printer(id, name, ip, port, colorCapable, source, model, caps, ipv6, ppd,
                        obj.optBoolean("ppdManual")))
                } catch (_: Exception) {
                    // Skip malformed entry
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }
}
