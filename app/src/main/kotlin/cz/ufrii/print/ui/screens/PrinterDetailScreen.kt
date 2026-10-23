package cz.ufrii.print.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Scanner
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.ufrii.print.R
import cz.ufrii.print.status.InputType
import cz.ufrii.print.status.PaperInput
import cz.ufrii.print.status.PrinterCapabilities
import cz.ufrii.print.status.Toner
import cz.ufrii.print.ui.AppViewModel
import cz.ufrii.print.ui.components.HeaderBar
import cz.ufrii.print.ui.components.IconAction
import cz.ufrii.print.ui.components.LevelBar
import cz.ufrii.print.ui.components.ListRow
import cz.ufrii.print.ui.components.OutlineButton
import cz.ufrii.print.ui.components.Overline
import cz.ufrii.print.ui.components.PrinterHero
import cz.ufrii.print.ui.components.Spinner
import cz.ufrii.print.ui.components.UfrCard
import cz.ufrii.print.ui.components.ValueText
import cz.ufrii.print.ui.components.cmykOrder
import cz.ufrii.print.ui.components.tonerColorValue
import cz.ufrii.print.ui.components.tonerName
import cz.ufrii.print.ui.components.TONER_LOW_PERCENT
import cz.ufrii.print.ui.theme.Ufr

@Composable
fun PrinterDetailScreen(
    vm: AppViewModel,
    id: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onCalibrate: (String) -> Unit,
    onShare: (String) -> Unit,
    onDriver: (String) -> Unit
) {
    val c = Ufr.colors
    val ctx = LocalContext.current
    val printers by vm.printers.collectAsState()
    val defaultId by vm.defaultId.collectAsState()
    val statuses by vm.status.collectAsState()
    val loading by vm.statusLoading.collectAsState()
    val redetecting by vm.redetecting.collectAsState()
    val printer = printers.firstOrNull { it.id == id }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(id) { vm.refreshStatus(id) }
    if (printer == null) { onBack(); return }
    val status = statuses[id]
    val caps = printer.capabilities

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)
    ) {
        HeaderBar(printer.name, onBack = onBack) {
            if (id in loading) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Spinner(16.dp) }
            else IconAction(Icons.Outlined.Refresh, stringResource(R.string.cd_refresh), { vm.refreshStatus(id) })
            IconAction(Icons.Outlined.Edit, stringResource(R.string.cd_edit), { onEdit(id) })
        }
        PrinterHero(printer, status, id in loading, onClick = { vm.refreshStatus(id) })

        // --- Toners ---------------------------------------------------------
        if (status?.toners?.isNotEmpty() == true) {
            Overline(stringResource(R.string.detail_toners), Modifier.padding(top = 26.dp, bottom = 10.dp))
            UfrCard {
                val list = cmykOrder(status.toners)
                list.forEachIndexed { i, t -> TonerRow(t, divider = i < list.lastIndex) { copy(ctx, t.searchText) } }
            }
            Text(stringResource(R.string.detail_toner_note), style = Ufr.type.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = c.dim, modifier = Modifier.padding(top = 8.dp))
        }

        // --- Paper inputs -------------------------------------------------
        val inputs = status?.inputs?.takeIf { it.isNotEmpty() } ?: caps?.inputs.orEmpty()
        if (inputs.isNotEmpty()) {
            Overline(stringResource(R.string.detail_inputs), Modifier.padding(top = 26.dp, bottom = 10.dp))
            UfrCard {
                inputs.forEachIndexed { i, inp -> InputRow(inp, divider = i < inputs.lastIndex) }
            }
        }

        // --- Capabilities -------------------------------------------------
        Overline(stringResource(R.string.detail_caps), Modifier.padding(top = 26.dp, bottom = 10.dp))
        if (caps != null) {
            CapabilitySummary(caps, pageCount = status?.pageCount)
            Text(
                stringResource(R.string.detail_detected_at,
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(java.util.Date(caps.detectedAt))),
                style = Ufr.type.dataSmall, color = c.dim, modifier = Modifier.padding(top = 8.dp)
            )
        } else Text(stringResource(R.string.detail_caps_unknown), style = Ufr.type.bodySmall, color = c.dim)

        // --- Addresses -------------------------------------------------------
        Overline(stringResource(R.string.detail_addresses), Modifier.padding(top = 26.dp, bottom = 10.dp))
        UfrCard {
            val v4 = printer.ip.takeIf { !cz.ufrii.print.PrinterAddress.isIpv6(it) }
            val v6 = printer.ipv6 ?: printer.ip.takeIf { cz.ufrii.print.PrinterAddress.isIpv6(it) }
            ListRow("IPv4", trailing = { ValueText(v4?.let { "$it:${printer.port}" } ?: stringResource(R.string.detail_addr_none), mono = true) })
            ListRow("IPv6", subtitle = if (v6 == null) stringResource(R.string.detail_addr_v6_hint) else null,
                trailing = { ValueText(v6 ?: stringResource(R.string.detail_addr_none), mono = true) }, divider = false)
        }

        // --- Driver (PPD) ------------------------------------------------------
        Overline(stringResource(R.string.detail_driver), Modifier.padding(top = 26.dp, bottom = 10.dp))
        val ppd = cz.ufrii.print.PpdCatalog.forPrinter(ctx, printer)
        UfrCard {
            ListRow(
                ppd.nick ?: ppd.short,
                subtitle = stringResource(when {
                    printer.ppdManual -> R.string.detail_driver_manual
                    printer.ppd != null -> R.string.detail_driver_auto
                    else -> R.string.detail_driver_default
                }) + " · " + stringResource(if (cz.ufrii.print.PpdCatalog.autoDuplex(ctx, printer)) R.string.detail_driver_duplex
                    else R.string.detail_driver_no_duplex),
                trailing = { Text(stringResource(R.string.detail_driver_change), style = Ufr.type.label, color = c.link) },
                divider = false,
                onClick = { onDriver(id) }
            )
        }

        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (printer.id != defaultId) {
                OutlineButton(stringResource(R.string.detail_set_default), { vm.setDefault(id) }, strong = true)
            }
            if (id in redetecting) {
                Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    Spinner(16.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.detail_redetecting), style = Ufr.type.label, color = c.mut)
                }
            } else {
                OutlineButton(stringResource(R.string.detail_redetect), { vm.redetect(id) })
            }
            // Tests: one page (mode from Home), and the scanner answering over the network.
            val job by vm.job.collectAsState()
            val scanTest by vm.scannerTest.collectAsState()
            val mode by cz.ufrii.print.AppSettings.defaultMode.collectAsState()
            val printing = job is cz.ufrii.print.ui.JobState.Running
            if (printing) {
                OutlineButton(stringResource(R.string.detail_test_print_cancel), vm::cancelJob, destructive = true)
            } else {
                // A test shows everything the printer can do: colour when it has it.
                OutlineButton(stringResource(R.string.detail_test_print), { vm.printTestPage(cz.ufrii.print.ColorMode.COLOR, id) },
                    icon = Icons.Outlined.Print)
            }
            (job as? cz.ufrii.print.ui.JobState.Done)?.let { d ->
                Text(if (d.result.ok) stringResource(R.string.detail_test_print_ok) else d.result.message,
                    style = Ufr.type.bodySmall, color = if (d.result.ok) c.ok else c.errTx)
            }
            val st = scanTest[id]
            OutlineButton(stringResource(if (st == AppViewModel.ScannerTest.Running) R.string.detail_test_scanner_running
                else R.string.detail_test_scanner), { vm.testScanner(id) }, icon = Icons.Outlined.Scanner,
                enabled = st != AppViewModel.ScannerTest.Running)
            when (st) {
                is AppViewModel.ScannerTest.Ok -> Text(stringResource(R.string.detail_test_scanner_ok,
                    st.caps.device ?: "?",
                    st.caps.sources.map { if (it == cz.ufrii.print.ScanSource.GLASS) stringResource(R.string.scan_glass) else stringResource(R.string.cap_adf) }.joinToString(),
                    "${st.caps.resolutions.first()}–${st.caps.resolutions.last()}"),
                    style = Ufr.type.bodySmall, color = c.ok)
                AppViewModel.ScannerTest.Failed -> Text(stringResource(R.string.detail_test_scanner_failed),
                    style = Ufr.type.bodySmall, color = c.errTx)
                else -> {}
            }
            OutlineButton(stringResource(R.string.calib_title), { onCalibrate(id) })
            OutlineButton(stringResource(R.string.share_title), { onShare(id) }, icon = Icons.Outlined.QrCode2)
            OutlineButton(stringResource(R.string.detail_remove), { confirmRemove = true }, destructive = true)
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.detail_remove_q, printer.name)) },
            confirmButton = {
                TextButton({ confirmRemove = false; vm.removePrinter(id); onBack() }) {
                    Text(stringResource(R.string.detail_remove), color = c.err)
                }
            },
            dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(R.string.action_cancel), color = c.ink) } }
        )
    }
}

@Composable
private fun TonerRow(t: Toner, divider: Boolean, onCopy: () -> Unit) {
    val c = Ufr.colors
    val pct = t.levelPercent
    ListRow(
        title = tonerName(t.color) + (pct?.let { " · $it %" } ?: ""),
        subtitle = t.searchText,
        subtitleMono = true,
        titleColor = if (pct != null && pct <= TONER_LOW_PERCENT) c.err else c.ink,
        leading = {
            Box(Modifier.size(14.dp).clip(CircleShape).background(tonerColorValue(t.color, onDark = c.isDark)))
        },
        trailing = { IconAction(Icons.Outlined.ContentCopy, stringResource(R.string.cd_copy_toner), onCopy, size = 18.dp) },
        divider = false
    )
    LevelBar(pct?.div(100f), tonerColorValue(t.color, onDark = c.isDark), height = 6.dp,
        modifier = Modifier.padding(bottom = 14.dp))
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line2))
}

@Composable
private fun InputRow(inp: PaperInput, divider: Boolean) {
    val c = Ufr.colors
    val typeLabel = stringResource(when (inp.type) {
        InputType.MANUAL -> R.string.input_manual
        InputType.CASSETTE -> R.string.input_cassette
        InputType.OTHER -> R.string.input_other
    })
    val (state, color) = when (inp.hasPaper) {
        true -> stringResource(R.string.input_has_paper) to c.ok
        false -> stringResource(R.string.input_empty) to (if (inp.type == InputType.MANUAL) c.dim else c.err)
        null -> stringResource(R.string.unknown) to c.dim
    }
    val cap = inp.maxCapacity?.let { " · " + stringResource(R.string.input_capacity, it) } ?: ""
    ListRow(
        title = typeLabel,
        subtitle = inp.name + cap,
        subtitleMono = true,
        trailing = { Text(state, style = Ufr.type.labelSmall, color = color) },
        divider = divider
    )
}

/** Capability table (shared with the add-printer result). */
@Composable
fun CapabilitySummary(caps: PrinterCapabilities, pageCount: Long? = null) {
    val yes = stringResource(R.string.yes)
    val no = stringResource(R.string.no)
    val unknown = stringResource(R.string.unknown)
    fun b(v: Boolean?) = when (v) { true -> yes; false -> no; null -> unknown }
    UfrCard {
        ListRow(stringResource(R.string.cap_model), trailing = { ValueText(caps.model ?: unknown, mono = true) })
        ListRow(stringResource(R.string.cap_color), trailing = { ValueText(b(caps.color)) })
        ListRow(stringResource(R.string.cap_inputs), trailing = {
            ValueText(if (caps.inputs.isEmpty()) unknown else caps.inputs.size.toString())
        })
        ListRow(stringResource(R.string.cap_duplex), trailing = { ValueText(b(caps.duplex)) },
            subtitle = if (caps.duplex == false) stringResource(R.string.cap_duplex_manual) else null)
        ListRow(stringResource(R.string.cap_scanner), trailing = { ValueText(b(caps.scanner)) })
        ListRow(stringResource(R.string.cap_adf), trailing = { ValueText(b(caps.adf)) },
            subtitle = if (caps.adf == null) stringResource(R.string.cap_adf_later) else null,
            divider = pageCount != null)
        if (pageCount != null) {
            ListRow(stringResource(R.string.cap_pages), trailing = { ValueText("%,d".format(pageCount).replace(',', ' '), mono = true) },
                divider = false)
        }
    }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("toner", text))
    Toast.makeText(ctx, ctx.getString(R.string.copied, text), Toast.LENGTH_SHORT).show()
}
