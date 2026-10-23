package cz.ufrii.print.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.ufrii.print.Printer
import cz.ufrii.print.R
import cz.ufrii.print.ui.AppViewModel
import cz.ufrii.print.ui.components.EmptyBlock
import cz.ufrii.print.ui.components.HeaderBar
import cz.ufrii.print.ui.components.IconAction
import cz.ufrii.print.ui.components.ListRow
import cz.ufrii.print.ui.components.OutlineButton
import cz.ufrii.print.ui.components.Overline
import cz.ufrii.print.ui.components.Spinner
import cz.ufrii.print.ui.components.StatusDot
import cz.ufrii.print.ui.components.UfrCard
import cz.ufrii.print.ui.components.heroLabel
import cz.ufrii.print.ui.components.heroStateOf
import cz.ufrii.print.ui.theme.Fixed
import cz.ufrii.print.ui.theme.Ufr

@Composable
fun PrintersScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: (ip: String?, name: String?) -> Unit
) {
    val c = Ufr.colors
    val printers by vm.printers.collectAsState()
    val defaultId by vm.defaultId.collectAsState()
    val statuses by vm.status.collectAsState()
    val loading by vm.statusLoading.collectAsState()
    val found by vm.found.collectAsState()
    val discovering by vm.discovering.collectAsState()

    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }
    LaunchedEffect(printers.size) { printers.forEach { vm.refreshStatus(it.id) } }

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)
    ) {
        HeaderBar(stringResource(R.string.printers_title), onBack = onBack) {
            IconAction(Icons.Outlined.Add, stringResource(R.string.cd_add_printer), { onAdd(null, null) }, tint = c.ink)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (printers.isEmpty()) {
                EmptyBlock(stringResource(R.string.printers_empty))
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                printers.forEach { p ->
                    val s = statuses[p.id]
                    SavedPrinterCard(
                        printer = p,
                        isDefault = p.id == defaultId,
                        stateColor = heroLabel(heroStateOf(s, p.id in loading)).second,
                        onClick = { onOpen(p.id) }
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Overline(stringResource(if (discovering) R.string.printers_searching else R.string.printers_found))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).height(1.dp).background(c.line))
                Spacer(Modifier.width(10.dp))
                if (discovering) Spinner()
                else IconAction(Icons.Outlined.Refresh, stringResource(R.string.cd_search_again), vm::startDiscovery, size = 18.dp)
            }
            if (found.isEmpty()) {
                if (!discovering) EmptyBlock(stringResource(R.string.printers_none_found))
            } else {
                UfrCard {
                    found.forEachIndexed { i, p ->
                        ListRow(
                            title = p.name,
                            subtitle = "${p.ip} · ${p.model}",
                            subtitleMono = true,
                            leading = { Icon(Icons.Outlined.Print, null, tint = c.mut, modifier = Modifier.size(22.dp)) },
                            trailing = {
                                Text(stringResource(R.string.action_add),
                                    style = Ufr.type.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = c.link,
                                    modifier = Modifier.clip(RoundedCornerShape(100.dp))
                                        .clickable(role = Role.Button) { onAdd(p.ip, p.name) }
                                        .padding(horizontal = 10.dp, vertical = 12.dp))
                            },
                            divider = i < found.lastIndex
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.printers_scan_note), style = Ufr.type.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = c.dim)
        }
        Spacer(Modifier.height(12.dp))
        OutlineButton(stringResource(R.string.printers_manual), { onAdd(null, null) }, icon = Icons.Outlined.Keyboard,
            strong = true, height = 54.dp)
    }
}

/** Saved printer in the inverse hero style (§5 Tiskárny). */
@Composable
private fun SavedPrinterCard(printer: Printer, isDefault: Boolean, stateColor: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val c = Ufr.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.inv).border(1.dp, c.invLine, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Print, null, tint = Fixed.tonerY, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(printer.name, style = Ufr.type.header, color = c.invInk)
            val suffix = if (isDefault) " · " + stringResource(R.string.printer_default) else ""
            Text("${printer.ip}:${printer.port}$suffix", style = Ufr.type.dataSmall, color = c.invMut)
        }
        StatusDot(stateColor, size = 8.dp)
    }
}
