package cz.ufrii.print

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cz.ufrii.print.status.AlertKind
import cz.ufrii.print.status.PrinterProbe
import cz.ufrii.print.status.PrinterStatus
import cz.ufrii.print.status.TonerColor
import java.util.concurrent.TimeUnit

/**
 * Periodic (15 min, WorkManager minimum) SNMP check of the default printer. Notifies when
 * the set of problems changes (toner low/empty, paper out, jam, cover open) and clears the
 * notification when the printer is fine again. "Unreachable" is not reported — the phone
 * is often simply not on the printer's Wi-Fi.
 */
class StatusWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val NAME = "printer-status"
        private const val PREFS = "ufr_status_worker"
        private const val KEY_LAST = "last_problems"

        fun schedule(ctx: Context, on: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!on) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<StatusWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** Problems worth a notification, as stable keys + Czech/English text. */
        fun problems(ctx: Context, s: PrinterStatus): List<Pair<String, String>> {
            if (!s.reachable) return emptyList()
            val out = mutableListOf<Pair<String, String>>()
            s.alerts.forEach { a ->
                val res = when (a.kind) {
                    AlertKind.JAM -> R.string.state_jam
                    AlertKind.COVER_OPEN -> R.string.state_cover
                    AlertKind.PAPER_EMPTY, AlertKind.TRAY_MISSING -> R.string.state_paper
                    AlertKind.TONER_EMPTY -> R.string.notify_toner_empty
                    else -> null
                }
                if (res != null) out += a.kind.name to ctx.getString(res)
            }
            s.toners.forEach { t ->
                val pct = t.levelPercent ?: return@forEach
                if (pct > 15) return@forEach
                val name = ctx.getString(when (t.color) {
                    TonerColor.BLACK -> R.string.toner_black
                    TonerColor.CYAN -> R.string.toner_cyan
                    TonerColor.MAGENTA -> R.string.toner_magenta
                    TonerColor.YELLOW -> R.string.toner_yellow
                    else -> R.string.toner_other
                })
                out += "TONER_${t.color.name}_LOW" to ctx.getString(R.string.notify_toner_low, name, pct)
            }
            return out.distinctBy { it.first }
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val printer = PrinterStore.defaultPrinter(ctx)?.let { PrinterAddress.resolve(ctx, it) } ?: return Result.success()
        val status = runCatching { PrinterProbe.readStatus(printer.ip) }.getOrNull() ?: return Result.success()
        if (!status.reachable) return Result.success()

        val probs = problems(ctx, status)
        val key = probs.joinToString(",") { it.first }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getString(KEY_LAST, "")
        if (key != last) {
            if (probs.isEmpty()) Notifications.cancelStatus(ctx)
            else Notifications.statusAlert(ctx, printer.name, probs.joinToString(" · ") { it.second })
            prefs.edit().putString(KEY_LAST, key).apply()
        }
        return Result.success()
    }
}
