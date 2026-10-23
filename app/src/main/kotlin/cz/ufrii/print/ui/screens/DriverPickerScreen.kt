package cz.ufrii.print.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.ufrii.print.PpdCatalog
import cz.ufrii.print.PpdInfo
import cz.ufrii.print.R
import cz.ufrii.print.ui.AppViewModel
import cz.ufrii.print.ui.components.HeaderBar
import cz.ufrii.print.ui.components.ListRow
import cz.ufrii.print.ui.components.Overline
import cz.ufrii.print.ui.theme.Ufr

/** Pick the printer model (PPD) by hand; the suggestion from the printer's report is on top. */
@Composable
fun DriverPickerScreen(vm: AppViewModel, id: String, onBack: () -> Unit) {
    val c = Ufr.colors
    val ctx = LocalContext.current
    val printer = vm.printer(id) ?: return
    val all = remember { PpdCatalog.all(ctx) }
    val current = remember(printer.ppd) { PpdCatalog.forPrinter(ctx, printer) }
    val suggested = remember(printer) {
        printer.capabilities?.let { caps ->
            PpdCatalog.match(ctx, listOf(caps.mdl, caps.model, caps.sysDescr, printer.name), caps.color ?: printer.colorCapable)
        }
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(query) {
        val q = PpdCatalog.norm(query)
        if (q.isEmpty()) all else all.filter { p -> PpdCatalog.norm(p.short).contains(q) || p.aliases.any { it.contains(q) } }
    }

    fun pick(p: PpdInfo) { vm.setPpd(id, p.file); onBack() }

    LazyColumn(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp)
    ) {
        item {
            HeaderBar(stringResource(R.string.driver_title), onBack = onBack)
            Text(stringResource(R.string.driver_body, all.size), style = Ufr.type.bodySmall, color = c.mut)
            Spacer(Modifier.height(14.dp))
            Field(stringResource(R.string.driver_search), query, { query = it }, "MF4550", keyboard = KeyboardType.Text)
            if (suggested != null && query.isBlank()) {
                Overline(stringResource(R.string.driver_suggested), Modifier.padding(top = 20.dp, bottom = 4.dp))
                DriverRow(suggested, suggested.file == current.file) { pick(suggested) }
            }
            Spacer(Modifier.height(12.dp))
        }
        items(shown, key = { it.file }) { p -> DriverRow(p, p.file == current.file) { pick(p) } }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun DriverRow(p: PpdInfo, selected: Boolean, onClick: () -> Unit) {
    val c = Ufr.colors
    val traits = listOfNotNull(
        stringResource(if (p.color) R.string.driver_color else R.string.driver_mono),
        if (p.duplex) stringResource(R.string.driver_duplex) else null,
        p.sources.count { it != cz.ufrii.print.PaperSource.AUTO }.takeIf { it > 0 }?.let { stringResource(R.string.driver_trays, it) }
    ).joinToString(" · ")
    ListRow(
        p.short,
        subtitle = traits,
        trailing = if (selected) ({ Icon(Icons.Outlined.CheckCircle, null, tint = c.acc, modifier = Modifier.size(20.dp)) }) else null,
        onClick = onClick
    )
}
