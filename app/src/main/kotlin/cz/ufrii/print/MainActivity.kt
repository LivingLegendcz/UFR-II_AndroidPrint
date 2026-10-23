package cz.ufrii.print

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import cz.ufrii.print.ui.AppViewModel
import cz.ufrii.print.ui.Routes
import cz.ufrii.print.ui.UfrApp

private const val TAG = "CanonPrint"

/**
 * Launcher + share/VIEW target. Hosts the Compose UI (docs/design, etapa D1);
 * print logic lives in [PrintJobs], printer state in [AppViewModel].
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppSettings.load(this)
        JobHistory.load(this)
        Notifications.ensureChannels(this)
        if (AppSettings.statusNotify.value) StatusWorker.schedule(this, true)
        // Fresh start: nothing can still be using scans from an earlier run.
        if (savedInstanceState == null) Thread { ScanJobs.sweep(applicationContext, 60 * 60_000L) }.start()
        Log.i(TAG, "[main] onCreate intent=${intent?.action}")
        if (savedInstanceState == null) handleIntent(intent)
        setContent { UfrApp(vm, Routes.HOME, onExit = ::finish) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "[main] onNewIntent intent=${intent.action}")
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Printers may have been edited from the system Print settings (AddPrinterActivity).
        vm.reloadPrinters()
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) vm.setPending(uri, intent.type) else AppLog.err("ACTION_SEND bez EXTRA_STREAM")
            }
            Intent.ACTION_VIEW -> intent.data?.let {
                if (it.scheme == SettingsShare.SCHEME) vm.setImportLink(it.toString()) else vm.setPending(it, intent.type)
            }
        }
    }
}
