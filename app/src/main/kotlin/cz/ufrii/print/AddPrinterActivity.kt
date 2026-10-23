package cz.ufrii.print

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import cz.ufrii.print.ui.AppViewModel
import cz.ufrii.print.ui.Routes
import cz.ufrii.print.ui.UfrApp

/**
 * Entry point from the system Print settings (settingsActivity / addPrintersActivity in
 * res/xml/print_service_capabilities.xml). Opens the Compose printer management
 * directly; back on the start screen returns to the system settings.
 */
class AddPrinterActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppSettings.load(this)
        setContent { UfrApp(vm, Routes.PRINTERS, onExit = ::finish) }
    }
}
