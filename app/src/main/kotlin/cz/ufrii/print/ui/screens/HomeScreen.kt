package cz.ufrii.print.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UploadFile
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.ufrii.print.AppSettings
import cz.ufrii.print.R
import cz.ufrii.print.ui.AppViewModel
import cz.ufrii.print.ui.JobState
import cz.ufrii.print.ui.components.IconAction
import cz.ufrii.print.ui.components.Logo
import cz.ufrii.print.ui.components.ModeSegment
import cz.ufrii.print.ui.components.OutlineButton
import cz.ufrii.print.ui.components.Overline
import cz.ufrii.print.ui.components.PrimaryButton
import cz.ufrii.print.ui.components.PrinterHero
import cz.ufrii.print.ui.components.Spinner
import cz.ufrii.print.ui.components.TextAction
import cz.ufrii.print.ui.theme.Ufr

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onSettings: () -> Unit,
    onPrinter: (String?) -> Unit,
    onLog: () -> Unit
) {
    val ctx = LocalContext.current
    val c = Ufr.colors
    val printers by vm.printers.collectAsState()
    val defaultId by vm.defaultId.collectAsState()
    val statuses by vm.status.collectAsState()
    val loading by vm.statusLoading.collectAsState()
    val mode by AppSettings.defaultMode.collectAsState()
    val pending by vm.pending.collectAsState()
    val unsupported by vm.unsupported.collectAsState()
    var scanInfo by remember { mutableStateOf(false) }
    val job by vm.job.collectAsState()

    val printer = printers.firstOrNull { it.id == defaultId } ?: printers.firstOrNull()
    val colorAvailable = printer?.capabilities?.color ?: printer?.colorCapable ?: true
    val busy = job is JobState.Running

    LaunchedEffect(printer?.id) { printer?.id?.let { vm.refreshStatus(it) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setPending(uri, ctx.contentResolver.getType(uri))
    }

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(Modifier.weight(1f))
            IconAction(Icons.Outlined.Tune, stringResource(R.string.cd_settings), onSettings)
        }

        PrinterHero(
            printer = printer,
            status = printer?.let { statuses[it.id] },
            loading = printer?.id in loading,
            onClick = { onPrinter(printer?.id) }
        )

        Spacer(Modifier.height(16.dp))
        ModeSegment(
            mode = if (colorAvailable) mode else cz.ufrii.print.ColorMode.MONO,
            onSelect = { AppSettings.setDefaultMode(ctx, it) },
            colorAvailable = colorAvailable
        )

        Spacer(Modifier.height(16.dp))
        when (val j = job) {
            is JobState.Running -> JobRunningCard(j.title)
            is JobState.Done -> JobDoneCard(j, onDismiss = vm::dismissJob, onLog = onLog)
            JobState.Idle -> {}
        }

        unsupported?.let { u ->
            UnsupportedFileCard(u.name, onOpen = {
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(u.uri, u.mime ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { ctx.startActivity(Intent.createChooser(view, ctx.getString(R.string.unsupported_open))) }
                vm.clearUnsupported()
            }, onClear = vm::clearUnsupported)
            Spacer(Modifier.height(10.dp))
        }

        val p = pending
        if (p != null && job !is JobState.Running) {
            PendingFileCard(
                name = p.name,
                isImage = p.isImage,
                enabled = printer != null && !busy,
                onPrint = { vm.printPending(if (colorAvailable) mode else cz.ufrii.print.ColorMode.MONO) },
                onClear = vm::clearPending
            )
            Spacer(Modifier.height(10.dp))
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChooseFileCard(enabled = !busy) {
                picker.launch(arrayOf("*/*"))
            }
            ActionCard(
                icon = Icons.Outlined.DocumentScanner,
                text = stringResource(R.string.home_scan),
                sub = stringResource(R.string.home_scan_sub),
                enabled = true
            ) { scanInfo = true }
            ActionCard(
                icon = Icons.Outlined.CheckCircle,
                text = stringResource(R.string.home_test_page),
                enabled = printer != null && !busy
            ) { vm.printTestPage(if (colorAvailable) mode else cz.ufrii.print.ColorMode.MONO) }
        }
    }

    if (scanInfo) {
        AlertDialog(
            onDismissRequest = { scanInfo = false },
            title = { Text(stringResource(R.string.home_scan)) },
            text = { Text(stringResource(R.string.scan_coming)) },
            confirmButton = { TextButton({ scanInfo = false }) { Text("OK", color = c.ink) } }
        )
    }
}

/** Accent card "Vybrat soubor" (§5 Hlavní). */
@Composable
private fun ChooseFileCard(enabled: Boolean, onClick: () -> Unit) {
    val c = Ufr.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.acc)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.UploadFile, null, tint = c.onAcc, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(R.string.home_choose_file),
                style = Ufr.type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = Ufr.type.header.fontSize * 1.06f),
                color = c.onAcc)
            Text(stringResource(R.string.home_choose_file_sub), style = Ufr.type.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = c.accSub)
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, text: String, enabled: Boolean, sub: String? = null, onClick: () -> Unit) {
    val c = Ufr.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = c.dim, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(text, style = Ufr.type.label, color = if (enabled) c.ink else c.dim)
            if (sub != null) Text(sub, style = Ufr.type.labelSmall.copy(fontWeight = FontWeight.Normal), color = c.dim)
        }
    }
}

@Composable
private fun UnsupportedFileCard(name: String, onOpen: () -> Unit, onClear: () -> Unit) {
    val c = Ufr.colors
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.line, shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Description, null, tint = c.dim, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Overline(stringResource(R.string.unsupported_title))
                Text(name, style = Ufr.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
            }
            IconAction(Icons.Outlined.Close, stringResource(R.string.cd_clear), onClear, size = 20.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.unsupported_body), style = Ufr.type.bodySmall, color = c.mut)
        Spacer(Modifier.height(12.dp))
        OutlineButton(stringResource(R.string.unsupported_open), onOpen, strong = true)
    }
}

@Composable
private fun PendingFileCard(name: String, isImage: Boolean, enabled: Boolean, onPrint: () -> Unit, onClear: () -> Unit) {
    val c = Ufr.colors
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.ink, shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Description, null, tint = c.dim, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Overline(stringResource(if (isImage) R.string.pending_image else R.string.pending_pdf))
                Text(name, style = Ufr.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
            }
            IconAction(Icons.Outlined.Close, stringResource(R.string.cd_clear), onClear, size = 20.dp)
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(stringResource(R.string.action_print), onPrint, icon = Icons.Outlined.Print, enabled = enabled, height = 52.dp)
    }
}

@Composable
private fun JobRunningCard(title: String) {
    val c = Ufr.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.line, shape).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spinner(size = 20.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(R.string.job_printing), style = Ufr.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
            Text(title, style = Ufr.type.dataSmall, color = c.dim)
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun JobDoneCard(j: JobState.Done, onDismiss: () -> Unit, onLog: () -> Unit) {
    val c = Ufr.colors
    val ok = j.result.ok
    val shape = RoundedCornerShape(if (ok) 20.dp else 24.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (ok) c.card else c.errBg)
            .border(1.dp, if (ok) c.line else c.errLine, shape)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
                tint = if (ok) c.ok else c.err, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (ok) R.string.job_done else R.string.job_failed),
                    style = Ufr.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                Text(
                    if (ok) "${j.title} · %.1f s".format(j.result.elapsedMs / 1000.0) else j.result.message,
                    style = Ufr.type.dataSmall, color = if (ok) c.dim else c.errTx
                )
            }
            IconAction(Icons.Outlined.Close, stringResource(R.string.cd_clear), onDismiss, size = 20.dp)
        }
        if (!ok) {
            Spacer(Modifier.height(8.dp))
            TextAction(stringResource(R.string.action_show_log), onLog)
        }
    }
    Spacer(Modifier.height(10.dp))
}
