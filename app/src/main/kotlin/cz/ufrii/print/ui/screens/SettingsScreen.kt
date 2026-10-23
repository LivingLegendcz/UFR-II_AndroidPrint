package cz.ufrii.print.ui.screens

import android.content.Intent
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
import cz.ufrii.print.ColorMode
import cz.ufrii.print.R
import cz.ufrii.print.ui.components.HeaderBar
import cz.ufrii.print.ui.components.ListRow
import cz.ufrii.print.ui.components.Overline
import cz.ufrii.print.ui.components.UfrCard
import cz.ufrii.print.ui.components.ValueText
import cz.ufrii.print.ui.theme.Appearance
import cz.ufrii.print.ui.theme.Ufr

/** Label + all options visible as chips (instead of a hidden cycling value). */
@Composable
private fun OptionGroup(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Ufr.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Text(label, style = Ufr.type.label, color = c.ink)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
fun SettingsScreen(onBack: () -> Unit, onLog: () -> Unit, onPrinters: () -> Unit) {
    val c = Ufr.colors
    val ctx = LocalContext.current
    val mode by AppSettings.defaultMode.collectAsState()
    val appearance by AppSettings.appearance.collectAsState()
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
            OptionGroup(
                stringResource(R.string.settings_appearance),
                listOf(stringResource(R.string.appearance_system), stringResource(R.string.appearance_light),
                    stringResource(R.string.appearance_dark)),
                selected = appearance.ordinal
            ) { AppSettings.setAppearance(ctx, Appearance.entries[it]) }
        }

        Overline(stringResource(R.string.settings_advanced), Modifier.padding(top = 26.dp, bottom = 10.dp))
        UfrCard {
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
