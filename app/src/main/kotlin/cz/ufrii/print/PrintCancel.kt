package cz.ufrii.print

/**
 * Stops a running print job: the filter pipeline [bind]s a kill action (destroy the filter,
 * close the printer socket) while it runs; [cancel] fires it, also when it comes before the bind.
 *
 * The token travels with the worker thread ([current]): every print path is a blocking call
 * chain on one thread (PrintJobs inside withContext, the PrintService executor), so the deep
 * pipeline finds it without passing it through every signature.
 */
class CancelToken {
    @Volatile var cancelled = false
        private set
    private var action: (() -> Unit)? = null

    fun bind(kill: () -> Unit) {
        synchronized(this) { action = kill }
        if (cancelled) kill()
    }

    fun unbind() = synchronized(this) { action = null }

    fun cancel() {
        cancelled = true
        synchronized(this) { action }?.invoke()
    }

    companion object {
        private val local = ThreadLocal<CancelToken?>()
        /** Every job that is printing now (app and PrintService) — for "Zrušit" in a notification. */
        private val running = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<CancelToken, Boolean>())

        fun cancelAll() = running.toList().forEach { it.cancel() }

        /** The token of the job running on this thread, if any. */
        fun current(): CancelToken? = local.get()

        /** Runs [block] with [token] as this thread's current token. */
        fun <T> with(token: CancelToken, block: () -> T): T {
            enter(token)
            try { return block() } finally { exit() }
        }

        /** [enter] / [exit] pair for code that cannot be wrapped in a lambda (labelled returns). */
        fun enter(token: CancelToken) { local.set(token); running.add(token) }
        fun exit() { local.get()?.let { running.remove(it) }; local.remove() }
    }
}

/**
 * Remote Scanner mode on the printer panel holds print jobs back: a small job is taken in full
 * and printed only when scanning ends, a big one stops being read (the connection stalls) until
 * then. The printer reports nothing different over SNMP in that mode, so:
 *  - a stalled connection is kept open while [waiting] tells what to do (ProotLauncher sets it);
 *  - after sending, the page counter must move; while it does not and the printer stays idle,
 *    [waiting] asks to end scanning, and the job is waited for — never sent again (the printer
 *    still has it; sending again would print it twice).
 */
object StallRetry {
    private const val OID_PAGE_COUNT = "1.3.6.1.2.1.43.10.2.1.4.1.1"
    private const val OID_PRINTER_STATUS = "1.3.6.1.2.1.25.3.5.1.1.1"
    /** hrPrinterStatus idle(3) this long after sending with no new page = the job is held back. */
    private const val IDLE_HELD_MS = 25_000L
    private const val WAIT_MS = 10 * 60_000L

    private val _waiting = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Title of the job the printer holds back (scan mode on the panel), else null. */
    val waiting: kotlinx.coroutines.flow.StateFlow<String?> = _waiting

    /** Shown / cleared by the pipeline (stalled connection) and by the page check below. */
    fun setWaiting(ctx: android.content.Context, title: String?) {
        if (_waiting.value == title) return
        _waiting.value = title
        if (title != null) {
            AppLog.err("Tiskárna úlohu drží — nejspíš je na panelu režim skenování. Čekám na jeho ukončení…")
            Notifications.scanModeWait(ctx, title)
        } else Notifications.cancelScanModeWait(ctx)
    }

    /** Runs [attempt] (one complete pass, blocking) to the printer at [ip] and waits until a page comes out. */
    fun run(ctx: android.content.Context, title: String, ip: String, attempt: () -> String): String {
        val token = CancelToken.current()
        current = title
        try {
            val before = pageCount(ip)
            val result = attempt()
            if (token?.cancelled == true || !ManualDuplexJob.passOk(result) || before == null) return result
            return when (printed(ctx, title, ip, before, token)) {
                Outcome.CANCELLED -> result + "\nFAIL: zrušeno — úloha už je v tiskárně a vytiskne se po ukončení skenování"
                Outcome.TIMEOUT -> result.also { AppLog.err("Tiskárna stranu zatím nevytiskla — úloha čeká v tiskárně") }
                else -> result
            }
        } finally {
            current = null
            setWaiting(ctx, null)
        }
    }

    /** Title of the job being sent now, for the pipeline's stall message. */
    @Volatile var current: String? = null
        private set

    private enum class Outcome { PRINTED, UNKNOWN, TIMEOUT, CANCELLED }

    private fun snmp(ip: String) = runCatching {
        kotlinx.coroutines.runBlocking {
            cz.ufrii.print.status.Snmp.get(ip, listOf(OID_PAGE_COUNT, OID_PRINTER_STATUS), timeoutMs = 1500)
        }
    }.getOrNull()

    private fun pageCount(ip: String): Long? =
        (snmp(ip)?.get(OID_PAGE_COUNT) as? cz.ufrii.print.status.SnmpValue.Int)?.v

    /** Polls until the page counter moves; asks to end scan mode while the printer idles without printing. */
    private fun printed(ctx: android.content.Context, title: String, ip: String, before: Long, token: CancelToken?): Outcome {
        val start = System.currentTimeMillis()
        var idleSince = start
        while (System.currentTimeMillis() - start < WAIT_MS) {
            val until = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < until) {
                if (token?.cancelled == true) return Outcome.CANCELLED
                Thread.sleep(200)
            }
            val m = snmp(ip) ?: return Outcome.UNKNOWN
            val count = (m[OID_PAGE_COUNT] as? cz.ufrii.print.status.SnmpValue.Int)?.v ?: return Outcome.UNKNOWN
            if (count > before) {
                if (_waiting.value != null) AppLog.ok("Skenování ukončeno, tiskárna tiskne")
                return Outcome.PRINTED
            }
            val status = (m[OID_PRINTER_STATUS] as? cz.ufrii.print.status.SnmpValue.Int)?.v
            val now = System.currentTimeMillis()
            if (status == 3L) {
                if (now - idleSince > IDLE_HELD_MS) setWaiting(ctx, title)
            } else idleSince = now
        }
        return Outcome.TIMEOUT
    }
}
