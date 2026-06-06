package cz.ufrii.print

import android.content.Context
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
    val model: String = "Canon MF8030Cn"
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

    const val DEFAULT_IP   = "192.168.0.62"
    const val DEFAULT_PORT = 9100

    private const val PREFS_NAME = "canon_printers"
    private const val KEY_LIST   = "manual_printers"

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Generate a new unique printer id. */
    fun newManualId(): String = UUID.randomUUID().toString()

    /**
     * Load persisted manual printers.
     * Seed-on-first-run: if nothing is stored yet, creates and persists a
     * default Canon MF8030Cn entry so callers always get at least one printer.
     */
    fun loadManual(ctx: Context): List<Printer> {
        val raw = prefs(ctx).getString(KEY_LIST, null)
        val parsed = parseJson(raw)

        if (parsed.isEmpty()) {
            // First run — seed with default printer and persist it
            val default = Printer(
                id           = newManualId(),
                name         = "Canon MF8030Cn (default)",
                ip           = DEFAULT_IP,
                port         = DEFAULT_PORT,
                colorCapable = true,
                source       = PrinterSource.MANUAL,
                model        = "Canon MF8030Cn"
            )
            val seeded = listOf(default)
            saveManual(ctx, seeded)
            return seeded
        }

        return parsed
    }

    /** Serialize [list] and commit to SharedPreferences. */
    fun saveManual(ctx: Context, list: List<Printer>) {
        val json = serializeJson(list)
        prefs(ctx).edit().putString(KEY_LIST, json).commit()
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
                    val model = obj.optString("model", "Canon MF8030Cn")
                    result.add(Printer(id, name, ip, port, colorCapable, source, model))
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
