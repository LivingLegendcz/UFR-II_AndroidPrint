package cz.ufrii.print.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.ufrii.print.AppSettings
import cz.ufrii.print.HistoryEntry
import cz.ufrii.print.JobHistory
import cz.ufrii.print.Notifications
import cz.ufrii.print.R
import cz.ufrii.print.ui.components.EmptyBlock
import cz.ufrii.print.ui.components.HeaderBar
import cz.ufrii.print.ui.components.IconAction
import cz.ufrii.print.ui.components.ListRow
import cz.ufrii.print.ui.components.Logo
import cz.ufrii.print.ui.components.OutlineButton
import cz.ufrii.print.ui.components.PrimaryButton
import cz.ufrii.print.ui.components.UfrCard
import cz.ufrii.print.ui.theme.Ufr
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val c = Ufr.colors
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { JobHistory.load(ctx) }
    val entries by JobHistory.entries.collectAsState()
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
        .padding(horizontal = 20.dp)) {
        HeaderBar(stringResource(R.string.history_title), onBack = onBack) {
            if (entries.isNotEmpty()) IconAction(Icons.Outlined.DeleteOutline, stringResource(R.string.log_clear),
                { JobHistory.clear(ctx) })
        }
        if (entries.isEmpty()) {
            EmptyBlock(stringResource(R.string.history_empty))
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(entries) { e ->
                val via = stringResource(when (e.via) {
                    HistoryEntry.VIA_SYSTEM -> R.string.history_via_system
                    HistoryEntry.VIA_SCAN -> R.string.history_via_scan
                    else -> R.string.history_via_app
                })
                ListRow(
                    e.title,
                    subtitle = listOfNotNull(fmt.format(Date(e.time)), e.printer.ifBlank { null }, via,
                        if (e.duplex) stringResource(R.string.history_duplex) else null,
                        e.message.ifBlank { null }).joinToString(" · "),
                    subtitleColor = if (e.ok) c.dim else c.errTx,
                    leading = {
                        Icon(if (e.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
                            tint = if (e.ok) c.ok else c.err, modifier = Modifier.size(20.dp))
                    }
                )
            }
        }
    }
}
