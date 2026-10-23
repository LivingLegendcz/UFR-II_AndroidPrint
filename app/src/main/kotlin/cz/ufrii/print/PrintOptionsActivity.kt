package cz.ufrii.print

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.print.PrintJobInfo
import android.print.PrinterInfo
import android.printservice.PrintService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.ufrii.print.status.PrinterProbe
import cz.ufrii.print.status.PrinterStatus
import cz.ufrii.print.ui.components.HeaderBar
import cz.ufrii.print.ui.components.PaperSourcePicker
import cz.ufrii.print.ui.components.PrimaryButton
import cz.ufrii.print.ui.components.Section
import cz.ufrii.print.ui.theme.Ufr
import cz.ufrii.print.ui.theme.UfrTheme

/**
 * "More options" of the system print dialog (print_service_capabilities.xml →
 * advancedPrintOptionsActivity). Adds what the standard dialog cannot express:
 * the paper source (PPD InputSlot), returned as an advanced option of the PrintJobInfo.
 */
class PrintOptionsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppSettings.load(this)

        val jobInfo: PrintJobInfo? = parcelable(PrintService.EXTRA_PRINT_JOB_INFO)
        val printerInfo: PrinterInfo? = parcelable(PrintService.EXTRA_PRINTER_INFO)
        val printer = printerInfo?.id?.localId?.let { id -> PrinterStore.loadManual(this).firstOrNull { it.id == id } }
        val initial = jobInfo?.getAdvancedStringOption(ADV_INPUT_SLOT)?.let { PaperSource.fromPpd(it) }
            ?: AppSettings.defaultSource.value

        fun deliver(source: PaperSource) {
            if (jobInfo == null) { setResult(Activity.RESULT_CANCELED); return }
            val updated = PrintJobInfo.Builder(jobInfo).apply { putAdvancedOption(ADV_INPUT_SLOT, source.ppd) }.build()
            setResult(Activity.RESULT_OK, Intent().putExtra(PrintService.EXTRA_PRINT_JOB_INFO, updated))
        }
        deliver(initial)

        setContent {
            val appearance by AppSettings.appearance.collectAsState()
            UfrTheme(appearance) {
                val c = Ufr.colors
                var source by remember { mutableStateOf(initial) }
                var status by remember { mutableStateOf<PrinterStatus?>(null) }
                LaunchedEffect(printer?.ip) {
                    printer?.let { p ->
                        status = runCatching {
                            val live = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                PrinterAddress.resolve(this@PrintOptionsActivity, p)
                            }
                            PrinterProbe.readStatus(live.ip)
                        }.getOrNull()
                    }
                }
                Column(
                    Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
                        .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                ) {
                    HeaderBar(stringResource(R.string.options_title), onBack = ::finish, backIcon = Icons.Outlined.Close)
                    printer?.let { Text(it.name, style = Ufr.type.data, color = c.dim) }
                    Section(stringResource(R.string.print_source)) {
                        PaperSourcePicker(source, {
                            source = it
                            deliver(it)
                            AppSettings.setDefaultSource(this@PrintOptionsActivity, it)
                        }, status, available = printer?.let { PpdCatalog.forPrinter(this@PrintOptionsActivity, it).sources }
                            ?: listOf(PaperSource.AUTO, PaperSource.MANUAL, PaperSource.CASSETTE))
                    }
                    Section(stringResource(R.string.cap_duplex)) {
                        Text(stringResource(R.string.options_duplex_note), style = Ufr.type.bodySmall, color = c.mut)
                    }
                    Spacer(Modifier.height(28.dp))
                    PrimaryButton(stringResource(R.string.action_done), { deliver(source); finish() })
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> parcelable(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, T::class.java) else intent.getParcelableExtra(key)
}
