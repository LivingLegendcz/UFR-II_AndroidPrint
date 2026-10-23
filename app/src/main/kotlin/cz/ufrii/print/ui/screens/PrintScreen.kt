package cz.ufrii.print.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.ufrii.print.AppSettings
import cz.ufrii.print.ColorMode
import cz.ufrii.print.DuplexCalibrations
import cz.ufrii.print.ManualDuplexJob
import cz.ufrii.print.PaperSource
import cz.ufrii.print.PrintOptions
import cz.ufrii.print.R
import cz.ufrii.print.parsePageRange
import cz.ufrii.print.ui.AppViewModel
import cz.ufrii.print.ui.JobState
import cz.ufrii.print.ui.components.Choice
import cz.ufrii.print.ui.components.ChoiceList
import cz.ufrii.print.ui.components.HeaderBar
import cz.ufrii.print.ui.components.LevelBar
import cz.ufrii.print.ui.components.ListRow
import cz.ufrii.print.ui.components.ModeSegment
import cz.ufrii.print.ui.components.OutlineButton
import cz.ufrii.print.ui.components.PaperSourcePicker
import cz.ufrii.print.ui.components.PrimaryButton
import cz.ufrii.print.ui.components.Section
import cz.ufrii.print.ui.components.Spinner
import cz.ufrii.print.ui.components.Stepper
import cz.ufrii.print.ui.components.TextAction
import cz.ufrii.print.ui.components.UfrCard
import cz.ufrii.print.ui.components.UfrSwitch
import cz.ufrii.print.ui.theme.Ufr
import cz.ufrii.print.PpdCatalog

/** Print options for the pending file: mode, copies, pages, paper source, manual duplex. */
@Composable
fun PrintScreen(vm: AppViewModel, onBack: () -> Unit, onCalibrate: (String) -> Unit, onLog: () -> Unit) {
    val c = Ufr.colors
    val ctx = LocalContext.current
    val pending by vm.pending.collectAsState()
    val pageCount by vm.pendingPages.collectAsState()
    val job by vm.job.collectAsState()
    val statuses by vm.status.collectAsState()
    val defaultMode by AppSettings.defaultMode.collectAsState()
    val defaultSource by AppSettings.defaultSource.collectAsState()
    val printer = vm.defaultPrinter()
    val colorAvailable = printer?.capabilities?.color ?: printer?.colorCapable ?: true

    var mode by remember { mutableStateOf(if (colorAvailable) defaultMode else ColorMode.MONO) }
    var copies by remember { mutableIntStateOf(1) }
    var allPages by remember { mutableStateOf(true) }
    var range by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(defaultSource) }
    var duplex by remember { mutableStateOf(false) }

    LaunchedEffect(printer?.id) { printer?.id?.let { vm.refreshStatus(it) } }

    val file = pending
    val running = job as? JobState.Running
    val done = job as? JobState.Done

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
    ) {
        HeaderBar(stringResource(R.string.print_title), onBack = onBack)

        if (running != null) {
            PrintProgress(running)
            val waiting by cz.ufrii.print.StallRetry.waiting.collectAsState()
            if (waiting != null) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.scanmode_text), style = Ufr.type.bodySmall, color = c.errTx)
            }
            Spacer(Modifier.height(12.dp))
            OutlineButton(stringResource(R.string.action_cancel), vm::cancelJob, destructive = true)
            return@Column
        }
        if (done != null) {
            JobResultBlock(done, onClose = { vm.dismissJob(); onBack() }, onLog = onLog)
            return@Column
        }
        if (file == null || printer == null) {
            Text(stringResource(R.string.print_nothing), style = Ufr.type.body, color = c.mut)
            return@Column
        }

        UfrCard {
            ListRow(file.name,
                subtitle = listOfNotNull(pageCount?.let { stringResource(R.string.print_pages_n, it) }, printer.name)
                    .joinToString(" · "),
                divider = false)
        }

        Section(stringResource(R.string.print_mode)) {
            ModeSegment(mode, { mode = it }, colorAvailable)
        }

        if (!file.isImage) {
            Section(stringResource(R.string.print_copies)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.print_copies_sub), style = Ufr.type.bodySmall, color = c.mut,
                        modifier = Modifier.weight(1f))
                    Stepper(copies, { copies = it })
                }
            }
            Section(stringResource(R.string.print_pages)) {
                ChoiceList(
                    listOf(Choice(stringResource(R.string.print_pages_all), pageCount?.let { stringResource(R.string.print_pages_n, it) }),
                        Choice(stringResource(R.string.print_pages_range), stringResource(R.string.print_pages_range_sub))),
                    if (allPages) 0 else 1
                ) { allPages = it == 0 }
                if (!allPages) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(range, { range = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("1-3, 5") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = range.isNotBlank() && pageCount != null && parsePageRange(range, pageCount!!) == null)
                }
            }
        }

        val ppd = remember(printer.ppd) { PpdCatalog.forPrinter(ctx, printer) }
        val autoDuplex = remember(printer) { PpdCatalog.autoDuplex(ctx, printer) }
        Section(stringResource(R.string.print_source)) {
            PaperSourcePicker(source, { source = it }, statuses[printer.id], allowAuto = !duplex || autoDuplex,
                available = ppd.sources)
        }

        if (!file.isImage) {
            val dSource = ManualDuplexJob.duplexSource(source)
            val calibrated = DuplexCalibrations.get(ctx, printer.id, dSource) != null
            Section(stringResource(R.string.cap_duplex)) {
                UfrCard {
                    ListRow(
                        stringResource(if (autoDuplex) R.string.print_duplex_auto else R.string.print_duplex_manual),
                        subtitle = stringResource(if (autoDuplex) R.string.print_duplex_auto_sub else R.string.print_duplex_manual_sub),
                        trailing = {
                            UfrSwitch(duplex, {
                                duplex = it
                                if (it && !autoDuplex && source == PaperSource.AUTO) source = PaperSource.CASSETTE
                            }, stringResource(R.string.print_duplex_manual))
                        },
                        divider = duplex && !autoDuplex
                    )
                    if (duplex && !autoDuplex) {
                        ListRow(
                            stringResource(if (calibrated) R.string.duplex_calibrated else R.string.duplex_not_calibrated),
                            subtitle = stringResource(R.string.duplex_calibrate_action),
                            subtitleColor = if (calibrated) c.dim else c.link,
                            divider = false,
                            onClick = { onCalibrate(printer.id) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(26.dp))
        val pages = if (file.isImage || allPages) null else pageCount?.let { parsePageRange(range, it) }
        val rangeOk = file.isImage || allPages || pages != null
        PrimaryButton(stringResource(R.string.action_print), {
            val src = if (duplex && !autoDuplex) ManualDuplexJob.duplexSource(source) else source
            vm.printPending(PrintOptions(mode, copies, src, duplex, pages))
        }, icon = Icons.Outlined.Print, enabled = rangeOk)
    }
}

@Composable
private fun PrintProgress(r: JobState.Running) {
    val c = Ufr.colors
    UfrCard(padding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(20.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(R.string.job_printing), style = Ufr.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                Text(r.title, style = Ufr.type.dataSmall, color = c.dim)
            }
        }
        Spacer(Modifier.height(14.dp))
        LevelBar(if (r.total > 0) (r.page + 0.5f) / r.total else null, c.acc, height = 6.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            listOfNotNull(
                r.pass?.let { stringResource(R.string.print_pass, it) },
                if (r.total > 0) stringResource(R.string.print_page_of, r.page + 1, r.total) else stringResource(R.string.print_preparing)
            ).joinToString(" · "),
            style = Ufr.type.dataSmall, color = c.mut
        )
    }
}

@Composable
private fun JobResultBlock(d: JobState.Done, onClose: () -> Unit, onLog: () -> Unit) {
    val c = Ufr.colors
    val ok = d.result.ok
    Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(if (ok) R.string.job_done else R.string.job_failed), style = Ufr.type.titleM, color = c.ink)
        Spacer(Modifier.height(8.dp))
        Text(if (ok) "${d.title} · %.1f s".format(d.result.elapsedMs / 1000.0) else d.result.message,
            style = Ufr.type.data, color = if (ok) c.dim else c.errTx)
        Spacer(Modifier.height(28.dp))
        PrimaryButton(stringResource(R.string.action_done), onClose)
        if (!ok) {
            Spacer(Modifier.height(8.dp))
            OutlineButton(stringResource(R.string.action_show_log), onLog)
        }
    }
}
