package cz.ufrii.print

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Paper source = PPD option `InputSlot` of CNRCUPSMF8000CZK.ppd (Auto / Manual / Cas1).
 * The driver (libcanonufr2r, cnpkmoduleufr2r) reads it from the CUPS options string.
 */
enum class PaperSource(val ppd: String, val cassette: Int = 0) {
    AUTO("Auto"), MANUAL("Manual"),
    // CASSETTE keeps its name: saved calibrations and settings use it for Cas1.
    CASSETTE("Cas1", 1), CAS2("Cas2", 2), CAS3("Cas3", 3), CAS4("Cas4", 4), CAS5("Cas5", 5), CAS6("Cas6", 6), CAS7("Cas7", 7),
    SIDE_DECK("SideDeck"), POD_DECK("PodDeck"), ENVELOPE("Env");

    val isCassette: Boolean get() = cassette > 0

    companion object {
        fun fromPpd(s: String?): PaperSource = fromPpdOrNull(s) ?: AUTO
        fun fromPpdOrNull(s: String?): PaperSource? = entries.firstOrNull { it.ppd == s }
    }
}

/** Advanced options carried in PrintJobInfo from [PrintOptionsActivity] to [CanonPrintService]. */
const val ADV_INPUT_SLOT = "cz.ufrii.print.InputSlot"
const val ADV_MANUAL_DUPLEX = "cz.ufrii.print.ManualDuplex"

/** Everything a print job needs besides the document and the printer. */
data class PrintOptions(
    val colorMode: ColorMode = ColorMode.COLOR,
    val copies: Int = 1,
    val source: PaperSource = PaperSource.AUTO,
    val manualDuplex: Boolean = false,
    /** 0-based PDF page indexes to print (null = all). */
    val pages: List<Int>? = null
)

// -----------------------------------------------------------------------------
// Manual duplex
// -----------------------------------------------------------------------------

/**
 * Result of the one-sheet calibration for one printer × tray (docs/21 §UX: "calibrated,
 * not guessed"). [faceUp]: re-insert the printed stack with the printed side up;
 * [rotate180]: the back side comes out upside down unless rendered rotated.
 */
data class DuplexCalibration(val faceUp: Boolean, val rotate180: Boolean)

object DuplexCalibrations {
    private const val PREFS = "ufr_duplex"

    private fun key(printerId: String, source: PaperSource) = "$printerId|${source.name}"

    fun get(ctx: Context, printerId: String, source: PaperSource): DuplexCalibration? {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(printerId, source), null)
            ?: return null
        return runCatching {
            val o = JSONObject(s)
            DuplexCalibration(o.getBoolean("faceUp"), o.getBoolean("rotate180"))
        }.getOrNull()
    }

    fun set(ctx: Context, printerId: String, source: PaperSource, c: DuplexCalibration) {
        val o = JSONObject().put("faceUp", c.faceUp).put("rotate180", c.rotate180)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(printerId, source), o.toString()).apply()
    }

    fun clear(ctx: Context, printerId: String, source: PaperSource) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(printerId, source)).apply()
    }
}

/** Page sequence for the two passes; -1 = blank page (keeps sheets paired). */
data class DuplexPlan(val fronts: List<Int>, val backs: List<Int>) {
    val sheets: Int get() = fronts.size
}

object DuplexPlanner {
    /**
     * Splits [pages] (× [copies], each copy starting on a new sheet) into front and back passes.
     *
     * The MF8030Cn delivers face-down, so the first printed sheet ends up at the bottom of
     * the output stack. Re-inserted **face up** the stack gets turned over → sheet 1 is on
     * top and feeds first → backs in normal order. Re-inserted **face down** (not turned
     * over) the last sheet feeds first → backs reversed. The manual feeder takes one sheet
     * at a time in the order the user is told (sheet 1 first).
     */
    fun plan(pages: List<Int>, copies: Int, source: PaperSource, cal: DuplexCalibration): DuplexPlan {
        val seq = mutableListOf<Int>()
        repeat(copies.coerceAtLeast(1)) {
            seq += pages
            if (pages.size % 2 == 1) seq += -1
        }
        val fronts = seq.filterIndexed { i, _ -> i % 2 == 0 }
        val backs = seq.filterIndexed { i, _ -> i % 2 == 1 }
        val reversed = source.isCassette && !cal.faceUp
        return DuplexPlan(fronts, if (reversed) backs.reversed() else backs)
    }
}

/** What the user has to do between the two passes; shown in-app and via a notification. */
data class DuplexPrompt(
    val id: Long,
    val title: String,
    val sheets: Int,
    val source: PaperSource,
    val faceUp: Boolean
)

/**
 * Hand-off between a print job waiting after its first pass (PrintService worker thread or
 * in-app coroutine) and the UI that tells the user how to re-insert the paper.
 */
object DuplexGate {
    private val _prompt = MutableStateFlow<DuplexPrompt?>(null)
    val prompt: StateFlow<DuplexPrompt?> = _prompt
    private var answer: CompletableDeferred<Boolean>? = null

    /** Suspends until the user continues (true) or cancels (false); 30 min timeout = cancel. */
    suspend fun await(p: DuplexPrompt): Boolean {
        val d = CompletableDeferred<Boolean>()
        synchronized(this) {
            answer?.complete(false)
            answer = d
            _prompt.value = p
        }
        return try {
            withTimeoutOrNull(30 * 60_000L) { d.await() } ?: false
        } finally {
            synchronized(this) { if (answer === d) { answer = null; _prompt.value = null } }
        }
    }

    /** Blocking variant for the PrintService executor thread. */
    fun awaitBlocking(p: DuplexPrompt): Boolean = runBlocking { await(p) }

    fun resolve(id: Long, proceed: Boolean) {
        synchronized(this) {
            if (_prompt.value?.id == id) answer?.complete(proceed)
        }
    }
}

/**
 * "1-3, 5, 8-" style page range (1-based, as printed on the page) → 0-based indexes in
 * the order given. Returns null when the text is empty or refers to pages outside 1..[count].
 */
fun parsePageRange(text: String, count: Int): List<Int>? {
    val parts = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val out = mutableListOf<Int>()
    for (p in parts) {
        val m = Regex("""^(\d+)?\s*-\s*(\d+)?$""").find(p)
        val (a, b) = when {
            m != null -> (m.groupValues[1].toIntOrNull() ?: 1) to (m.groupValues[2].toIntOrNull() ?: count)
            else -> (p.toIntOrNull() ?: return null).let { it to it }
        }
        if (a < 1 || b > count || a > b) return null
        for (i in a..b) out += i - 1
    }
    return out
}
