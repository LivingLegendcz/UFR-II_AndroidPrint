package cz.ufrii.print.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Scanner
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cz.ufrii.print.AppSettings
import cz.ufrii.print.AreaPreset
import cz.ufrii.print.R
import cz.ufrii.print.ScanDest
import cz.ufrii.print.ScanError
import cz.ufrii.print.ScanFormat
import cz.ufrii.print.ScanJobs
import cz.ufrii.print.ScanMode
import cz.ufrii.print.ScanSettings
import cz.ufrii.print.ScanSource
import cz.ufrii.print.ui.AppViewModel
import cz.ufrii.print.ui.PreviewState
import cz.ufrii.print.ui.ScanState
import cz.ufrii.print.ui.components.Choice
import cz.ufrii.print.ui.components.ChoiceList
import cz.ufrii.print.ui.components.HeaderBar
import cz.ufrii.print.ui.components.LevelBar
import cz.ufrii.print.ui.components.OutlineButton
import cz.ufrii.print.ui.components.PrimaryButton
import cz.ufrii.print.ui.components.Section
import cz.ufrii.print.ui.components.Spinner
import cz.ufrii.print.ui.components.TextAction
import cz.ufrii.print.ui.components.UfrCard
import cz.ufrii.print.ui.theme.Ufr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScanScreen(vm: AppViewModel, onBack: () -> Unit, onPrintCopy: () -> Unit) {
    val c = Ufr.colors
    val ctx = LocalContext.current
    val printers by vm.printers.collectAsState()
    val state by vm.scan.collectAsState()
    val allCaps by vm.scanCaps.collectAsState()
    val capsLoading by vm.scanCapsLoading.collectAsState()
    val capsFailed by vm.scanCapsFailed.collectAsState()
    val savedDest by AppSettings.scanDest.collectAsState()
    val savedFormat by AppSettings.scanFormat.collectAsState()
    val printer = printers.firstOrNull { it.id == vm.defaultPrinter()?.id }
    val caps = printer?.let { allCaps[it.id] }
    val preview by vm.preview.collectAsState()
    val preset by AppSettings.scanArea.collectAsState()
    var area by remember { mutableStateOf(AreaSel.FULL) }
    // A new preview starts with the frame around what lies on the glass (else the default from Settings).
    LaunchedEffect(preview) {
        val ready = preview as? PreviewState.Ready ?: return@LaunchedEffect
        val gw = caps?.glassW ?: return@LaunchedEffect
        val gh = caps.glassH ?: return@LaunchedEffect
        area = ready.suggested?.let { AreaSel.of(it) } ?: AreaSel.of(preset.area(gw, gh), gw, gh)
    }

    LaunchedEffect(printer?.id) { vm.loadScanCaps() }

    var source by remember { mutableStateOf(ScanSource.GLASS) }
    var mode by remember { mutableStateOf(ScanMode.COLOR) }
    var dpi by remember { mutableStateOf(300) }
    var dest by remember(savedDest) { mutableStateOf(savedDest) }
    var format by remember(savedFormat) { mutableStateOf(savedFormat) }

    // Keep the selection inside what the scanner offers.
    LaunchedEffect(caps) {
        caps ?: return@LaunchedEffect
        if (source !in caps.sources) source = caps.sources.first()
        if (mode !in caps.modes) mode = caps.modes.first()
        if (dpi !in caps.resolutions) dpi = caps.resolutions.lastOrNull { it <= 300 } ?: caps.resolutions.first()
    }

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
    ) {
        HeaderBar(stringResource(R.string.scan_title), onBack = { vm.resetScan(); onBack() })
        if (printer == null) {
            Text(stringResource(R.string.hero_no_printer_hint), style = Ufr.type.body, color = c.mut)
            return@Column
        }

        when (val s = state) {
            is ScanState.Scanning -> {
                ScanProgress(s, source)
                return@Column
            }
            is ScanState.Done -> {
                ScanResultBlock(vm, s, dest, format, onPrintCopy)
                return@Column
            }
            else -> {}
        }

        PanelStepsCard()

        (state as? ScanState.Failed)?.let { f ->
            Spacer(Modifier.height(12.dp))
            ScanErrorCard(f.error, f.message)
        }

        when {
            caps == null && capsLoading -> Row(Modifier.padding(top = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(18.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.scan_caps_loading), style = Ufr.type.label, color = c.mut)
            }
            caps == null -> {
                Spacer(Modifier.height(20.dp))
                Text(stringResource(if (capsFailed) R.string.scan_caps_failed else R.string.scan_caps_loading),
                    style = Ufr.type.bodySmall, color = if (capsFailed) c.errTx else c.mut)
                Spacer(Modifier.height(12.dp))
                OutlineButton(stringResource(R.string.calib_again), { vm.loadScanCaps(force = true) }, strong = true)
            }
            else -> {
                Section(stringResource(R.string.scan_source)) {
                    ChoiceList(caps.sources.map { s ->
                        when (s) {
                            ScanSource.GLASS -> Choice(stringResource(R.string.scan_glass), stringResource(R.string.scan_glass_sub))
                            ScanSource.FEEDER -> Choice(stringResource(R.string.cap_adf), stringResource(R.string.scan_feeder_sub))
                        }
                    }, caps.sources.indexOf(source)) { source = caps.sources[it] }
                }
                Section(stringResource(R.string.scan_mode)) {
                    ChoiceList(caps.modes.map { m ->
                        when (m) {
                            ScanMode.COLOR -> Choice(stringResource(R.string.mode_color))
                            ScanMode.GRAY -> Choice(stringResource(R.string.scan_gray))
                            ScanMode.LINEART -> Choice(stringResource(R.string.scan_lineart), stringResource(R.string.scan_lineart_sub))
                        }
                    }, caps.modes.indexOf(mode)) { mode = caps.modes[it] }
                }
                Section(stringResource(R.string.scan_resolution)) {
                    ChoiceList(caps.resolutions.map { d -> Choice("$d dpi", dpiHint(d)) },
                        caps.resolutions.indexOf(dpi)) { dpi = caps.resolutions[it] }
                    Spacer(Modifier.height(4.dp))
                    TextAction(stringResource(R.string.scan_caps_from_scanner), { vm.loadScanCaps(force = true) })
                }
                if (source == ScanSource.GLASS && caps.canCrop) {
                    Section(stringResource(R.string.scan_area)) {
                        AreaPicker(vm, preview, area, preset, caps.glassW!!, caps.glassH!!) { area = it }
                    }
                }
                Section(stringResource(R.string.scan_dest)) {
                    ChoiceList(
                        listOf(
                            Choice(stringResource(R.string.scan_dest_downloads), stringResource(R.string.scan_dest_downloads_sub)),
                            Choice(stringResource(R.string.scan_dest_choose), stringResource(R.string.scan_dest_choose_sub)),
                            Choice(stringResource(R.string.scan_dest_share), stringResource(R.string.scan_dest_share_sub)),
                            Choice(stringResource(R.string.scan_print_copy), stringResource(R.string.scan_dest_print_sub))
                        ),
                        dest.ordinal
                    ) { dest = ScanDest.entries[it] }
                }
                if (dest != ScanDest.PRINT) {
                    Section(stringResource(R.string.scan_format)) {
                        ChoiceList(
                            listOf(Choice("PDF", stringResource(R.string.scan_format_pdf_sub)),
                                Choice("JPEG", stringResource(R.string.scan_format_jpeg_sub)),
                                Choice("PNG", stringResource(R.string.scan_format_png_sub))),
                            format.ordinal
                        ) { format = ScanFormat.entries[it] }
                    }
                }
                Spacer(Modifier.height(26.dp))
                PrimaryButton(stringResource(R.string.scan_start), {
                    AppSettings.setScanOutput(ctx, dest, format)
                    // Preview frame when there is one, else the default area from Settings.
                    val chosen = when {
                        source != ScanSource.GLASS || !caps.canCrop -> null
                        preview is PreviewState.Ready -> area.takeIf { !it.isFull }?.toArea(caps.glassW!!, caps.glassH!!)
                        else -> preset.area(caps.glassW!!, caps.glassH!!)
                    }
                    vm.startScan(ScanSettings(source, mode, dpi, area = chosen))
                }, icon = Icons.Outlined.Scanner, enabled = preview !is PreviewState.Loading)
            }
        }
    }
}

@Composable
private fun dpiHint(d: Int): String = stringResource(when {
    d <= 75 -> R.string.scan_75_sub
    d <= 150 -> R.string.scan_150_sub
    d <= 300 -> R.string.scan_300_sub
    else -> R.string.scan_600_sub
})

/** The step on the printer panel, as key caps on the accent colour — it must not read like app text. */
@Composable
private fun PanelStepsCard() {
    val c = Ufr.colors
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.acc).padding(18.dp)) {
        Text(stringResource(R.string.scan_panel_title), style = Ufr.type.headline, color = c.onAcc)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeyCap("SCAN")
            Arrow()
            KeyCap("Remote Scanner")
            Arrow()
            KeyCap("OK")
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.scan_panel_body), style = Ufr.type.bodySmall, color = c.onAcc)
    }
}

@Composable
private fun KeyCap(text: String) {
    val c = Ufr.colors
    Text(text, style = Ufr.type.label.copy(fontWeight = FontWeight.Bold), color = c.invInk,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.onAcc).padding(horizontal = 10.dp, vertical = 8.dp))
}

@Composable
private fun Arrow() {
    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Ufr.colors.onAcc,
        modifier = Modifier.padding(horizontal = 4.dp).size(18.dp))
}

@Composable
private fun ScanProgress(s: ScanState.Scanning, source: ScanSource) {
    val c = Ufr.colors
    UfrCard(padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(20.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(R.string.scan_running), style = Ufr.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                Text(stringResource(R.string.scan_page_pct, s.page, s.percent), style = Ufr.type.dataSmall, color = c.dim)
            }
        }
        Spacer(Modifier.height(14.dp))
        LevelBar(s.percent / 100f, c.acc, height = 6.dp)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(if (source == ScanSource.FEEDER) R.string.scan_running_feeder else R.string.scan_running_glass),
            style = Ufr.type.bodySmall, color = c.mut)
    }
}

/** No preview → button; then the glass with a draggable frame, its size and quick actions. */
@Composable
private fun AreaPicker(vm: AppViewModel, preview: PreviewState?, area: AreaSel, preset: AreaPreset,
                       glassW: Float, glassH: Float, onArea: (AreaSel) -> Unit) {
    val c = Ufr.colors
    when (preview) {
        null -> {
            Text(stringResource(R.string.scan_area_default, areaPresetLabel(preset)), style = Ufr.type.label, color = c.ink)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.scan_preview_sub), style = Ufr.type.bodySmall, color = c.mut)
            Spacer(Modifier.height(10.dp))
            OutlineButton(stringResource(R.string.scan_preview), vm::startPreview, icon = Icons.Outlined.CropFree, strong = true)
        }
        PreviewState.Loading -> Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Spinner(18.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.scan_preview_running), style = Ufr.type.label, color = c.mut)
        }
        is PreviewState.Failed -> {
            ScanErrorCard(preview.error, preview.message, R.string.scan_preview_failed)
            Spacer(Modifier.height(10.dp))
            OutlineButton(stringResource(R.string.scan_preview_again), vm::startPreview, icon = Icons.Outlined.CropFree, strong = true)
        }
        is PreviewState.Ready -> {
            CropBox(preview.bitmap, area, onArea)
            Spacer(Modifier.height(8.dp))
            val mm = area.toArea(glassW, glassH)
            Text(stringResource(R.string.scan_area_size, mm.width.toInt(), mm.height.toInt()), style = Ufr.type.data, color = c.ink)
            Text(stringResource(R.string.scan_preview_hint), style = Ufr.type.bodySmall, color = c.dim)
            Row {
                TextAction(stringResource(R.string.scan_area_full), { onArea(AreaSel.FULL) }, Modifier.weight(1f))
                if (preview.suggested != null) {
                    TextAction(stringResource(R.string.scan_area_found), { onArea(AreaSel.of(preview.suggested)) }, Modifier.weight(1f))
                }
                TextAction(stringResource(R.string.scan_preview_again), vm::startPreview, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ScanErrorCard(error: ScanError, message: String, title: Int = R.string.scan_failed) {
    val c = Ufr.colors
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.errBg).border(1.dp, c.errLine, shape).padding(16.dp)) {
        Text(stringResource(title), style = Ufr.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(when (error) {
            ScanError.BUSY -> R.string.scan_err_busy
            ScanError.NO_PAPER -> R.string.scan_err_no_paper
            ScanError.JAM -> R.string.scan_err_jam
            ScanError.TIMEOUT -> R.string.scan_err_timeout
            ScanError.UNREACHABLE -> R.string.scan_err_unreachable
            ScanError.UNSUPPORTED -> R.string.scan_err_unsupported
            ScanError.OTHER -> R.string.scan_err_other
        }), style = Ufr.type.bodySmall, color = c.errTx)
        Spacer(Modifier.height(6.dp))
        Text(message, style = Ufr.type.dataSmall, color = c.dim)
    }
}

/**
 * Finished scan: the chosen destination runs once automatically; the other outputs stay
 * available as buttons.
 */
@Composable
private fun ScanResultBlock(vm: AppViewModel, d: ScanState.Done, dest: ScanDest, format: ScanFormat, onPrintCopy: () -> Unit) {
    val c = Ufr.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs = remember { ScanJobs(ctx) }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }
    val stamp = remember { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) }
    // Images = one file per page; a single picked location can hold only one file → PDF for more pages.
    val image = format.isImage
    val fileMime = if (image) format.mime else "application/pdf"
    val base = "sken-$stamp"

    suspend fun pdf(): File = jobs.toPdf(d.pages, d.settings.dpi, "$base.pdf")
    suspend fun images(): List<File> = d.pages.mapIndexed { i, p ->
        jobs.toImage(p, if (d.pages.size == 1) "$base.${format.ext}" else "$base-${i + 1}.${format.ext}", format)
    }
    fun uri(f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } catch (e: Exception) {
                outcome = "${e.javaClass.simpleName}: ${e.message}"
            } finally { busy = false }
        }
    }

    val pickImage = image && d.pages.size == 1
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(if (pickImage) format.mime else "application/pdf")
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        run {
            val f = if (pickImage) images().first() else pdf()
            outcome = ctx.getString(if (jobs.saveTo(f, target)) R.string.scan_saved_picked else R.string.scan_save_failed)
        }
    }

    fun toDownloads() = run {
        val files = if (image) images() else listOf(pdf())
        val ok = files.all { jobs.saveToDownloads(it, fileMime) != null }
        outcome = ctx.getString(if (ok) R.string.scan_saved else R.string.scan_save_failed)
    }

    fun share() = run {
        val files = if (image) images() else listOf(pdf())
        val send = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri(files[0]))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map(::uri)))
        }.setType(fileMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.scan_share)))
    }

    fun printCopy() = run {
        vm.printScanAsCopy(pdf())
        vm.resetScan()
        onPrintCopy()
    }

    // Run the destination chosen before scanning, once.
    LaunchedEffect(d) {
        when (dest) {
            ScanDest.DOWNLOADS -> toDownloads()
            ScanDest.CHOOSE -> picker.launch(if (pickImage) "$base.${format.ext}" else "$base.pdf")
            ScanDest.SHARE -> share()
            ScanDest.PRINT -> printCopy()
        }
    }

    Text(stringResource(R.string.scan_done, d.pages.size), style = Ufr.type.titleM, color = c.ink)
    outcome?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = Ufr.type.data, color = c.mut)
    }
    if (dest == ScanDest.CHOOSE && image && d.pages.size > 1) {
        Text(stringResource(R.string.scan_choose_pdf_note), style = Ufr.type.bodySmall, color = c.dim)
    }
    Spacer(Modifier.height(14.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(d.pages) { page -> Thumb(jobs, page) }
    }
    Spacer(Modifier.height(24.dp))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlineButton(stringResource(R.string.scan_dest_downloads), { toDownloads() }, icon = Icons.Outlined.Download,
            strong = true, enabled = !busy)
        OutlineButton(stringResource(R.string.scan_dest_choose), {
            picker.launch(if (pickImage) "$base.${format.ext}" else "$base.pdf")
        }, strong = true, enabled = !busy)
        OutlineButton(stringResource(R.string.scan_dest_share), { share() }, icon = Icons.Outlined.Share, strong = true, enabled = !busy)
        OutlineButton(stringResource(R.string.scan_print_copy), { printCopy() }, icon = Icons.Outlined.Print, strong = true, enabled = !busy)
        PrimaryButton(stringResource(R.string.scan_again), { vm.resetScan() }, icon = Icons.Outlined.Scanner, enabled = !busy)
    }
}

@Composable
private fun Thumb(jobs: ScanJobs, page: File) {
    val c = Ufr.colors
    val bmp by produceState<android.graphics.Bitmap?>(null, page) {
        value = withContext(Dispatchers.IO) { jobs.decode(page, sample = 8) }
    }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.size(width = 120.dp, height = 170.dp).clip(shape).background(c.card).border(1.dp, c.line, shape),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Spinner(16.dp)
    }
}
