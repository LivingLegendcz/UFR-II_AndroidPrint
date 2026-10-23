package cz.ufrii.print

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One finished print or scan (history screen). */
data class HistoryEntry(
    val time: Long,
    val title: String,
    val printer: String,
    val ok: Boolean,
    val message: String,
    /** [VIA_APP], [VIA_SYSTEM] (print dialog of another app) or [VIA_SCAN]. */
    val via: String,
    val duplex: Boolean = false
) {
    companion object {
        const val VIA_APP = "app"
        const val VIA_SYSTEM = "system"
        const val VIA_SCAN = "scan"
    }
}

/**
 * Last [MAX] jobs in SharedPreferences. Written by the in-app path, the PrintService and
 * scanning (all in the app process), exposed as a StateFlow for the UI.
 */
object JobHistory {
    private const val PREFS = "ufr_history"
    private const val KEY = "entries"
    private const val MAX = 50

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries
    @Volatile private var loaded = false

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        _entries.value = runCatching {
            val a = JSONArray(s ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                HistoryEntry(o.getLong("t"), o.getString("title"), o.optString("printer"), o.getBoolean("ok"),
                    o.optString("msg"), o.optString("via", HistoryEntry.VIA_APP), o.optBoolean("duplex"))
            }
        }.getOrDefault(emptyList())
        loaded = true
    }

    @Synchronized
    fun add(ctx: Context, e: HistoryEntry) {
        load(ctx)
        _entries.value = (listOf(e) + _entries.value).take(MAX)
        save(ctx)
    }

    @Synchronized
    fun clear(ctx: Context) {
        _entries.value = emptyList()
        save(ctx)
    }

    private fun save(ctx: Context) {
        val a = JSONArray()
        _entries.value.forEach { e ->
            a.put(JSONObject().put("t", e.time).put("title", e.title).put("printer", e.printer).put("ok", e.ok)
                .put("msg", e.message).put("via", e.via).put("duplex", e.duplex))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }
}
