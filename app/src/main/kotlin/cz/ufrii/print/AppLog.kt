package cz.ufrii.print

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "CanonPrint"

/** Severity of one app-log line; drives the colour in the log console. */
enum class LogLevel { INFO, OK, ACCENT, ERROR, DIM }

data class LogLine(val time: String, val level: LogLevel, val text: String)

/**
 * In-memory application log (session scope) shown on the Log screen.
 * The PrintService keeps its own persistent file (printservice.log), read separately.
 */
object AppLog {

    private const val MAX_LINES = 1000

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun i(text: String) = add(LogLevel.INFO, text)
    fun ok(text: String) = add(LogLevel.OK, text)
    fun acc(text: String) = add(LogLevel.ACCENT, text)
    fun err(text: String) = add(LogLevel.ERROR, text)
    fun dim(text: String) = add(LogLevel.DIM, text)

    @Synchronized
    fun add(level: LogLevel, text: String) {
        Log.i(TAG, "[app] $text")
        val now = fmt.format(Date())
        val added = text.lines().map { LogLine(now, level, it) }
        _lines.value = (_lines.value + added).takeLast(MAX_LINES)
    }

    fun clear() {
        _lines.value = emptyList()
    }

    /** Plain-text dump for the clipboard. */
    fun dump(): String = _lines.value.joinToString("\n") { "[${it.time}] ${it.text}" }
}
