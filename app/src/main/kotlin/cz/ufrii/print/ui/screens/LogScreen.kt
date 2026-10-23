package cz.ufrii.print.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cz.ufrii.print.AppLog
import cz.ufrii.print.LogLevel
import cz.ufrii.print.LogLine
import cz.ufrii.print.R
import cz.ufrii.print.ui.AppViewModel
import cz.ufrii.print.ui.components.HeaderBar
import cz.ufrii.print.ui.components.IconAction
import cz.ufrii.print.ui.components.LevelBar
import cz.ufrii.print.ui.components.OutlineButton
import cz.ufrii.print.ui.components.Spinner
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import cz.ufrii.print.ui.theme.Fixed
import cz.ufrii.print.ui.theme.Ufr
import java.io.File

private enum class LogFilter { APP, ERRORS, SERVICE }

@Composable
fun LogScreen(vm: AppViewModel, onBack: () -> Unit) {
    val c = Ufr.colors
    val ctx = LocalContext.current
    val appLines by AppLog.lines.collectAsState()
    var filter by remember { mutableStateOf(LogFilter.APP) }
    val diag by vm.diag.collectAsState()
    var serviceTick by remember { mutableIntStateOf(0) }
    val serviceLines = remember(serviceTick, filter) {
        val f = File(ctx.filesDir, "printservice.log")
        if (f.exists()) f.readLines().map { LogLine("", classify(it), it) } else emptyList()
    }
    val lines = when (filter) {
        LogFilter.APP -> appLines
        LogFilter.ERRORS -> (appLines + serviceLines).filter { it.level == LogLevel.ERROR }
        LogFilter.SERVICE -> serviceLines
    }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            HeaderBar(stringResource(R.string.log_title), onBack = onBack) {
                val filterName = stringResource(when (filter) {
                    LogFilter.APP -> R.string.log_filter_app
                    LogFilter.ERRORS -> R.string.log_filter_errors
                    LogFilter.SERVICE -> R.string.log_filter_service
                })
                IconAction(Icons.Outlined.ContentCopy, stringResource(R.string.cd_copy_log), {
                    // Copies exactly what the current filter shows.
                    val text = lines.joinToString("\n") { if (it.time.isEmpty()) it.text else "[${it.time}] ${it.text}" }
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ufr-log", text))
                    Toast.makeText(ctx, ctx.getString(R.string.log_copied_what, filterName, lines.size), Toast.LENGTH_SHORT).show()
                })
            }
        }
        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(stringResource(R.string.log_filter_app), filter == LogFilter.APP) { filter = LogFilter.APP }
            FilterChip(stringResource(R.string.log_filter_errors), filter == LogFilter.ERRORS) { filter = LogFilter.ERRORS }
            FilterChip(stringResource(R.string.log_filter_service), filter == LogFilter.SERVICE) {
                filter = LogFilter.SERVICE; serviceTick++
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(18.dp)).background(Fixed.console).padding(16.dp)
        ) {
            if (lines.isEmpty()) item { Text(stringResource(R.string.log_empty), style = Ufr.type.console, color = Fixed.consoleTime) }
            items(lines) { l -> ConsoleLine(l) }
        }
        diag?.let { d ->
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(14.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.log_diag_step, d.step + 1, d.total, d.label),
                        style = Ufr.type.dataSmall, color = c.mut)
                }
                Spacer(Modifier.height(8.dp))
                LevelBar((d.step + 0.5f) / d.total, c.acc, height = 6.dp)
            }
        }
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton(stringResource(R.string.log_diagnostics), vm::runDiagnostics, modifier = Modifier.weight(1f),
                height = 50.dp, enabled = diag == null)
            OutlineButton(stringResource(R.string.log_clear), {
                if (filter == LogFilter.SERVICE) {
                    File(ctx.filesDir, "printservice.log").delete(); serviceTick++
                } else AppLog.clear()
            }, modifier = Modifier.weight(1f), height = 50.dp)
        }
    }
}

/** Colour service-log lines by their content (the service writes plain text). */
private fun classify(s: String): LogLevel = when {
    s.contains("CHYBA") || s.contains("EXCEPTION") || s.contains("fail", ignoreCase = true) && !s.contains("bidiCommon") -> LogLevel.ERROR
    s.contains("HOTOVO") || s.contains("ok=true") -> LogLevel.OK
    s.contains("===") -> LogLevel.ACCENT
    s.contains("bidiCommon") -> LogLevel.DIM
    else -> LogLevel.INFO
}

@Composable
private fun ConsoleLine(l: LogLine) {
    val color = when (l.level) {
        LogLevel.OK -> Fixed.consoleOk
        LogLevel.ACCENT -> Fixed.consoleAcc
        LogLevel.ERROR -> Fixed.consoleErr
        LogLevel.DIM -> Fixed.consoleTime
        LogLevel.INFO -> Fixed.consoleText
    }
    Text(
        buildAnnotatedString {
            if (l.time.isNotEmpty()) withStyle(SpanStyle(color = Fixed.consoleTime)) { append(l.time); append("  ") }
            withStyle(SpanStyle(color = color)) { append(l.text) }
        },
        style = Ufr.type.console
    )
}

@Composable
private fun FilterChip(text: String, active: Boolean, onClick: () -> Unit) {
    val c = Ufr.colors
    val shape = RoundedCornerShape(100.dp)
    Text(
        text,
        style = Ufr.type.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = if (active) c.bg else c.mut,
        modifier = Modifier.clip(shape)
            .then(if (active) Modifier.background(c.ink) else Modifier.border(1.dp, c.line, shape))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}
