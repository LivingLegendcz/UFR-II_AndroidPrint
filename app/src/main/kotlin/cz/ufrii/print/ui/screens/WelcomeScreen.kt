package cz.ufrii.print.ui.screens

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.ufrii.print.AppSettings
import cz.ufrii.print.CanonPrintService
import cz.ufrii.print.Notifications
import cz.ufrii.print.R
import cz.ufrii.print.StatusWorker
import cz.ufrii.print.ui.AppViewModel
import cz.ufrii.print.ui.components.ListRow
import cz.ufrii.print.ui.components.Logo
import cz.ufrii.print.ui.components.Overline
import cz.ufrii.print.ui.components.PrimaryButton
import cz.ufrii.print.ui.components.UfrCard
import cz.ufrii.print.ui.components.UfrSwitch
import cz.ufrii.print.ui.theme.Ufr

/**
 * First run, in two blocks that must both be done before "Začít":
 * Telefon — print service on, notifications allowed, printer-status watching (choice);
 * Tiskárna — add one (search / IP) or load the setup from another phone's QR.
 * Steps are re-checked whenever the screen comes back from system settings.
 */
@Composable
fun WelcomeScreen(vm: AppViewModel, onImport: () -> Unit, onAddPrinter: () -> Unit, onDone: () -> Unit) {
    val c = Ufr.colors
    val ctx = LocalContext.current
    val printers by vm.printers.collectAsState()
    val statusNotify by AppSettings.statusNotify.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }

    var serviceVisited by remember { mutableStateOf(ServiceCheck.visited(ctx)) }
    val service = remember(tick, serviceVisited) { ServiceCheck.enabled(ctx) ?: serviceVisited }
    val notifOk = remember(tick) { Notifications.permitted(ctx) }
    // Notifications are a choice: allowed, or a deliberate "no" (then no duplex/status prompts).
    var notifDeclined by remember { mutableStateOf(ServiceCheck.notifDeclined(ctx)) }
    val notifDecided = notifOk || notifDeclined
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++; asked = true }

    fun openNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !asked) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Denied once (or switched off for the app): only the system screen can turn them on.
        else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    }
    fun setWatch(on: Boolean) {
        AppSettings.setStatusNotify(ctx, on)
        StatusWorker.schedule(ctx, on)
    }

    val printerOk = printers.isNotEmpty()
    val done = service && notifDecided && printerOk

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp)
    ) {
        Logo()
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.welcome_title), style = Ufr.type.display, color = c.ink)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.welcome_body), style = Ufr.type.body, color = c.mut)

        Overline(stringResource(R.string.welcome_phone), Modifier.padding(top = 24.dp, bottom = 10.dp))
        UfrCard {
            Step(stringResource(R.string.settings_service), stringResource(R.string.welcome_service_sub), service) {
                ServiceCheck.markVisited(ctx); serviceVisited = true
                ctx.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
            }
            Step(stringResource(R.string.welcome_notif),
                stringResource(when {
                    notifOk -> R.string.welcome_notif_ok
                    notifDeclined -> R.string.welcome_notif_declined
                    else -> R.string.welcome_notif_sub
                }), notifDecided,
                trailing = if (notifDecided) null else ({
                    Text(stringResource(R.string.welcome_notif_no), style = Ufr.type.label, color = c.link,
                        modifier = Modifier.clickable(role = Role.Button) {
                            ServiceCheck.setNotifDeclined(ctx); notifDeclined = true
                            if (statusNotify) setWatch(false)
                        }.padding(horizontal = 8.dp, vertical = 10.dp))
                })) {
                if (!notifOk) openNotifications()
            }
            ListRow(
                stringResource(R.string.settings_status_notify),
                subtitle = stringResource(R.string.settings_status_notify_sub),
                divider = false,
                trailing = { UfrSwitch(statusNotify && notifOk, { if (notifOk) setWatch(it) else openNotifications() },
                    stringResource(R.string.settings_status_notify)) }
            )
        }

        Overline(stringResource(R.string.welcome_printer), Modifier.padding(top = 24.dp, bottom = 10.dp))
        UfrCard {
            if (printerOk) {
                Step(printers.first().name, listOfNotNull(printers.first().ip, printers.first().ipv6).joinToString(" · "), true) {}
            }
            ListRow(stringResource(R.string.cd_add_printer), subtitle = stringResource(R.string.welcome_printer_sub),
                onClick = onAddPrinter)
            ListRow(stringResource(R.string.welcome_import), subtitle = stringResource(R.string.welcome_import_sub),
                divider = false, onClick = onImport)
        }

        Spacer(Modifier.height(28.dp))
        PrimaryButton(stringResource(R.string.welcome_start), {
            AppSettings.setOnboarded(ctx)
            onDone()
        }, enabled = done)
        if (!done) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.welcome_remaining, listOfNotNull(
                stringResource(R.string.settings_service).takeIf { !service },
                stringResource(R.string.welcome_notif).takeIf { !notifDecided },
                stringResource(R.string.welcome_printer).takeIf { !printerOk }
            ).joinToString(", ")), style = Ufr.type.bodySmall, color = c.dim)
        }
    }
}

/** A step row with a done / to-do mark. */
@Composable
private fun Step(title: String, sub: String, ok: Boolean, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = Ufr.colors
    ListRow(title, subtitle = sub,
        leading = {
            Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                tint = if (ok) c.ok else c.dim, modifier = Modifier.size(22.dp))
        },
        trailing = trailing,
        onClick = onClick)
}

/**
 * Is the UFR-II print service switched on? Android keeps the switched-off services in the
 * secure setting "disabled_print_services"; when it cannot be read, opening Settings → Tisk
 * once counts as done.
 */
private object ServiceCheck {
    private const val PREFS = "ufr_settings"
    private const val KEY = "print_service_visited"

    fun enabled(ctx: Context): Boolean? = runCatching {
        val me = ComponentName(ctx, CanonPrintService::class.java)
        val disabled = Settings.Secure.getString(ctx.contentResolver, "disabled_print_services").orEmpty()
        disabled.split(':').none { ComponentName.unflattenFromString(it) == me }
    }.getOrNull()

    fun visited(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun notifDeclined(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("notif_declined", false)
    fun setNotifDeclined(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("notif_declined", true).apply()
    fun markVisited(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}
