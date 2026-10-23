package cz.ufrii.print

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Notification channels: print results, the manual-duplex "turn the paper" prompt
 * (high importance — the job waits for it) and printer status alerts (StatusWorker).
 */
object Notifications {
    const val CH_JOBS = "jobs"
    const val CH_DUPLEX = "duplex"
    const val CH_STATUS = "status"

    private const val ID_DUPLEX = 1001
    private const val ID_JOB = 1002
    private const val ID_STATUS = 1003
    private const val ID_SCAN_MODE = 1004

    const val EXTRA_OPEN = "cz.ufrii.print.open"
    const val OPEN_DUPLEX = "duplex"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_JOBS, ctx.getString(R.string.ch_jobs),
            NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_DUPLEX, ctx.getString(R.string.ch_duplex),
            NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, ctx.getString(R.string.ch_status),
            NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun permitted(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun openApp(ctx: Context, what: String?, req: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (what != null) i.putExtra(EXTRA_OPEN, what)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Suppress("MissingPermission")
    private fun post(ctx: Context, id: Int, n: android.app.Notification) {
        if (!permitted(ctx)) return
        ensureChannels(ctx)
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    /** "Turn the paper" prompt with Continue / Cancel actions. */
    fun duplexPrompt(ctx: Context, p: DuplexPrompt, calibrated: Boolean) {
        fun action(act: String, req: Int) = PendingIntent.getBroadcast(
            ctx, req, Intent(ctx, DuplexActionReceiver::class.java).setAction(act).putExtra("id", p.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = duplexInstruction(ctx, p) + if (calibrated) "" else "\n" + ctx.getString(R.string.duplex_uncalibrated)
        val n = NotificationCompat.Builder(ctx, CH_DUPLEX)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentTitle(ctx.getString(R.string.duplex_notif_title, p.title))
            .setContentText(duplexInstruction(ctx, p))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setContentIntent(openApp(ctx, OPEN_DUPLEX, 1))
            .addAction(0, ctx.getString(R.string.duplex_continue), action(DuplexActionReceiver.CONTINUE, 2))
            .addAction(0, ctx.getString(R.string.action_cancel), action(DuplexActionReceiver.CANCEL, 3))
            .build()
        post(ctx, ID_DUPLEX, n)
    }

    fun cancelDuplex(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_DUPLEX)

    /** One-line instruction for the second pass (also used by the in-app screen). */
    fun duplexInstruction(ctx: Context, p: DuplexPrompt): String {
        val side = ctx.getString(if (p.faceUp) R.string.duplex_face_up else R.string.duplex_face_down)
        return when (p.source) {
            PaperSource.MANUAL -> ctx.getString(R.string.duplex_instr_manual, p.sheets, side)
            else -> ctx.getString(R.string.duplex_instr_cassette, p.sheets, side)
        }
    }

    fun jobResult(ctx: Context, title: String, ok: Boolean, message: String) {
        val n = NotificationCompat.Builder(ctx, CH_JOBS)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentTitle(ctx.getString(if (ok) R.string.job_done else R.string.job_failed))
            .setContentText(if (ok) title else "$title — $message")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, null, 4))
            .build()
        post(ctx, ID_JOB, n)
    }

    fun statusAlert(ctx: Context, printerName: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentTitle(printerName)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, null, 5))
            .build()
        post(ctx, ID_STATUS, n)
    }

    fun cancelStatus(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_STATUS)

    /** The printer takes no data (Remote Scanner on the panel): say what to do; the job retries by itself. */
    fun scanModeWait(ctx: Context, title: String) {
        val cancel = PendingIntent.getBroadcast(ctx, 6,
            Intent(ctx, DuplexActionReceiver::class.java).setAction(DuplexActionReceiver.CANCEL_PRINT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH_DUPLEX)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentTitle(ctx.getString(R.string.scanmode_title, title))
            .setContentText(ctx.getString(R.string.scanmode_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.scanmode_text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(openApp(ctx, null, 7))
            .addAction(0, ctx.getString(R.string.action_cancel), cancel)
            .build()
        post(ctx, ID_SCAN_MODE, n)
    }

    fun cancelScanModeWait(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_SCAN_MODE)

}

/** Continue / Cancel buttons of the duplex notification. */
class DuplexActionReceiver : BroadcastReceiver() {
    companion object {
        const val CONTINUE = "cz.ufrii.print.DUPLEX_CONTINUE"
        const val CANCEL = "cz.ufrii.print.DUPLEX_CANCEL"
        const val CANCEL_PRINT = "cz.ufrii.print.CANCEL_PRINT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CANCEL_PRINT) {
            AppLog.err("Tisk zrušen z oznámení")
            CancelToken.cancelAll()
            return
        }
        val id = intent.getLongExtra("id", -1)
        DuplexGate.resolve(id, intent.action == CONTINUE)
    }
}
