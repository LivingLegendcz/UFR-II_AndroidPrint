package cz.ufrii.print.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import cz.ufrii.print.Notifications
import cz.ufrii.print.PaperSource
import cz.ufrii.print.StatusWorker
import cz.ufrii.print.ui.components.UfrSwitch
import cz.ufrii.print.ui.components.paperSourceName
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.ufrii.print.AppSettings
import cz.ufrii.print.AreaPreset
import cz.ufrii.print.PaperFormat
import cz.ufrii.print.ScanArea
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import cz.ufrii.print.ColorMode
import cz.ufrii.print.R
import cz.ufrii.print.ui.components.HeaderBar
import cz.ufrii.print.ui.components.ListRow
import cz.ufrii.print.ui.components.Overline
import cz.ufrii.print.ui.components.UfrCard
import cz.ufrii.print.ui.components.ValueText
import cz.ufrii.print.ui.theme.Appearance
import cz.ufrii.print.ui.theme.Ufr

/** Default scan area: whole glass, a format at the glass mark, or a custom rectangle in mm. */
@Composable
private fun ScanAreaSetting() {
    val ctx = LocalContext.current
    val preset by AppSettings.scanArea.collectAsState()
    val formats = listOf(PaperFormat.A4, PaperFormat.A5, PaperFormat.A6, PaperFormat.B5, PaperFormat.LETTER)
    val custom = stringResource(R.string.settings_scan_area_custom)
    val selected = when {
        preset.custom != null -> formats.size + 1
        preset.format != null -> formats.indexOf(preset.format) + 1
        else -> 0
    }
    OptionGroup(
        stringResource(R.string.settings_scan_area),
        listOf(stringResource(R.string.scan_area_full)) + formats.map { it.label } + custom,
        selected,
        sub = stringResource(R.string.settings_scan_area_sub)
    ) { i ->
        AppSettings.setScanArea(ctx, when (i) {
            0 -> AreaPreset()
            formats.size + 1 -> AreaPreset(custom = preset.custom ?: ScanArea(0f, 0f, 100f, 150f))
            else -> AreaPreset(format = formats[i - 1])
        })
    }
    preset.custom?.let { a ->
        // Text kept per field so a half-typed number is not reformatted while typing.
        var w by remember { mutableStateOf(a.width.toInt().toString()) }
        var h by remember { mutableStateOf(a.height.toInt().toString()) }
        var l by remember { mutableStateOf(a.left.toInt().toString()) }
        var t by remember { mutableStateOf(a.top.toInt().toString()) }
        fun save() {
            val v = listOf(l, t, w, h).map { it.toFloatOrNull() }
            if (v.any { it == null } || v[2]!! < 5f || v[3]!! < 5f) return
            AppSettings.setScanArea(ctx, AreaPreset(custom = ScanArea(v[0]!!, v[1]!!, v[2]!!, v[3]!!)))
        }
        fun num(s: String) = s.filter(Char::isDigit).take(3)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            Box(Modifier.weight(1f)) { Field(stringResource(R.string.field_area_w), w, { w = num(it); save() }, "100", mono = true, keyboard = KeyboardType.Number) }
            Box(Modifier.weight(1f)) { Field(stringResource(R.string.field_area_h), h, { h = num(it); save() }, "150", mono = true, keyboard = KeyboardType.Number) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp)) {
            Box(Modifier.weight(1f)) { Field(stringResource(R.string.field_area_l), l, { l = num(it); save() }, "0", mono = true, keyboard = KeyboardType.Number) }
            Box(Modifier.weight(1f)) { Field(stringResource(R.string.field_area_t), t, { t = num(it); save() }, "0", mono = true, keyboard = KeyboardType.Number) }
        }
    }
}

/** Label + all options visible as chips (instead of a hidden cycling value); wraps when they do not fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionGroup(label: String, options: List<String>, selected: Int, sub: String? = null, onSelect: (Int) -> Unit) {
    val c = Ufr.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Text(label, style = Ufr.type.label, color = c.ink)
        sub?.let { Text(it, style = Ufr.type.bodySmall, color = c.dim, modifier = Modifier.padding(top = 2.dp)) }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, o ->
                val shape = RoundedCornerShape(100.dp)
                val active = i == selected
                Text(
                    o,
                    style = Ufr.type.labelSmall.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (active) c.bg else c.mut,
                    modifier = Modifier.clip(shape)
                        .then(if (active) Modifier.background(c.ink) else Modifier.border(1.dp, c.line, shape))
                        .clickable(role = Role.RadioButton) { onSelect(i) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLog: () -> Unit,
    onPrinters: () -> Unit,
    onHistory: () -> Unit,
    onWelcome: () -> Unit
) {
    val c = Ufr.colors
    val ctx = LocalContext.current
    val mode by AppSettings.defaultMode.collectAsState()
    val appearance by AppSettings.appearance.collectAsState()
    val source by AppSettings.defaultSource.collectAsState()
    val statusNotify by AppSettings.statusNotify.collectAsState()
    fun enableStatus(on: Boolean) {
        AppSettings.setStatusNotify(ctx, on)
        StatusWorker.schedule(ctx, on)
    }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enableStatus(granted)
    }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val rootfs = remember {
        runCatching { ctx.assets.open("rootfs.version").bufferedReader().readText().trim().take(12) }.getOrNull()
    }
    val chevron: @Composable () -> Unit = {
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.dim, modifier = Modifier.size(20.dp))
    }

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)
    ) {
        HeaderBar(onBack = onBack)
        Text(stringResource(R.string.settings_title), style = Ufr.type.titleL, color = c.ink,
            modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))

        UfrCard {
            ListRow(
                stringResource(R.string.settings_service),
                subtitle = stringResource(R.string.settings_service_sub),
                trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = c.dim, modifier = Modifier.size(20.dp)) },
                onClick = { ctx.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }
            )
            ListRow(stringResource(R.string.settings_printers), trailing = chevron, onClick = onPrinters)
            OptionGroup(
                stringResource(R.string.settings_default_mode),
                listOf(stringResource(R.string.mode_color), stringResource(R.string.mode_mono)),
                selected = if (mode == ColorMode.COLOR) 0 else 1
            ) { AppSettings.setDefaultMode(ctx, if (it == 0) ColorMode.COLOR else ColorMode.MONO) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line2))
            // Only the trays the default printer's model has (its PPD).
            val sources = remember {
                cz.ufrii.print.PrinterStore.defaultPrinter(ctx)?.let { cz.ufrii.print.PpdCatalog.forPrinter(ctx, it).sources }
                    ?: listOf(PaperSource.AUTO, PaperSource.MANUAL, PaperSource.CASSETTE)
            }
            OptionGroup(
                stringResource(R.string.settings_default_source),
                sources.map { paperSourceName(it) },
                selected = sources.indexOf(source).coerceAtLeast(0)
            ) { AppSettings.setDefaultSource(ctx, sources[it]) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line2))
            OptionGroup(
                stringResource(R.string.settings_appearance),
                listOf(stringResource(R.string.appearance_system), stringResource(R.string.appearance_light),
                    stringResource(R.string.appearance_dark)),
                selected = appearance.ordinal
            ) { AppSettings.setAppearance(ctx, Appearance.entries[it]) }
        }

        Overline(stringResource(R.string.settings_scan), Modifier.padding(top = 26.dp, bottom = 10.dp))
        UfrCard { ScanAreaSetting() }

        Overline(stringResource(R.string.settings_notifications), Modifier.padding(top = 26.dp, bottom = 10.dp))
        UfrCard {
            ListRow(
                stringResource(R.string.settings_status_notify),
                subtitle = stringResource(R.string.settings_status_notify_sub),
                divider = false,
                trailing = {
                    UfrSwitch(statusNotify, { on ->
                        if (on && Build.VERSION.SDK_INT >= 33 && !Notifications.permitted(ctx)) {
                            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else enableStatus(on)
                    }, stringResource(R.string.settings_status_notify))
                }
            )
        }

        Overline(stringResource(R.string.settings_advanced), Modifier.padding(top = 26.dp, bottom = 10.dp))
        UfrCard {
            ListRow(stringResource(R.string.history_title), trailing = chevron, onClick = onHistory)
            ListRow(stringResource(R.string.settings_welcome), trailing = chevron, onClick = onWelcome)
            ListRow(stringResource(R.string.settings_log), trailing = chevron, onClick = onLog)
            ListRow(
                stringResource(R.string.settings_env),
                subtitle = rootfs?.let { "rootfs $it…" },
                subtitleMono = true,
                divider = false
            )
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "UFR-II $version · cz.ufrii.print\n" + stringResource(R.string.settings_disclaimer),
            style = Ufr.type.dataSmall, color = c.dim, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
