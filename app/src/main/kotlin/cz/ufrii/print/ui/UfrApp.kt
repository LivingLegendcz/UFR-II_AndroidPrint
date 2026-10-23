package cz.ufrii.print.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import cz.ufrii.print.ui.theme.Ufr
import cz.ufrii.print.AppSettings
import cz.ufrii.print.DuplexGate
import cz.ufrii.print.ui.screens.CalibrationScreen
import cz.ufrii.print.ui.screens.DuplexPromptOverlay
import cz.ufrii.print.ui.screens.HistoryScreen
import cz.ufrii.print.ui.screens.PrintScreen
import cz.ufrii.print.ui.screens.ScanScreen
import cz.ufrii.print.ui.screens.WelcomeScreen
import cz.ufrii.print.ui.screens.ShareSettingsScreen
import cz.ufrii.print.ui.screens.DriverPickerScreen
import cz.ufrii.print.ui.screens.ImportSettingsScreen
import cz.ufrii.print.ui.screens.AddPrinterScreen
import cz.ufrii.print.ui.screens.HomeScreen
import cz.ufrii.print.ui.screens.LogScreen
import cz.ufrii.print.ui.screens.PrinterDetailScreen
import cz.ufrii.print.ui.screens.PrintersScreen
import cz.ufrii.print.ui.screens.SettingsScreen
import cz.ufrii.print.ui.theme.UfrTheme
import java.net.URLEncoder

object Routes {
    const val HOME = "home"
    const val PRINTERS = "printers"
    const val PRINTER = "printer/{id}"
    const val ADD = "add?id={id}&ip={ip}&ip6={ip6}&name={name}"
    const val SETTINGS = "settings"
    const val LOG = "log"
    const val PRINT = "print"
    const val CALIBRATE = "calibrate/{id}"
    const val SCAN = "scan"
    const val HISTORY = "history"
    const val WELCOME = "welcome"
    const val SHARE = "share/{id}"
    const val DRIVER = "driver/{id}"
    fun driver(id: String) = "driver/$id"
    const val IMPORT = "import"

    fun share(id: String) = "share/$id"

    fun calibrate(id: String) = "calibrate/$id"

    fun printer(id: String) = "printer/$id"
    fun add(id: String? = null, ip: String? = null, name: String? = null, ip6: String? = null): String {
        fun e(s: String?) = URLEncoder.encode(s ?: "", "UTF-8")
        return "add?id=${e(id)}&ip=${e(ip)}&ip6=${e(ip6)}&name=${e(name)}"
    }
}

/** Phone-width column centred on wide screens (landscape, tablets); the sides keep the background. */
@Composable
fun PageWidth(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ufr.colors.bg), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxHeight().widthIn(max = 640.dp)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) { content() }
    }
}

private fun NavGraphBuilder.page(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit
) = composable(route, arguments, enterTransition = { fadeIn(tween(150)) }, exitTransition = { fadeOut(tween(150)) }) { e ->
    PageWidth { content(e) }
}

/**
 * App root: theme + navigation. [startAt] is HOME for the launcher and PRINTERS when
 * the system Print settings open AddPrinterActivity; [onExit] closes the host activity
 * when back is pressed on the start destination.
 */
@Composable
fun UfrApp(vm: AppViewModel, startAt: String = Routes.HOME, onExit: () -> Unit) {
    val appearance by AppSettings.appearance.collectAsState()
    val duplexPrompt by DuplexGate.prompt.collectAsState()
    val onboarded by AppSettings.onboarded.collectAsState()
    val printers by vm.printers.collectAsState()
    UfrTheme(appearance) {
        val nav = rememberNavController()
        fun back() { if (!nav.popBackStack()) onExit() }
        // Onboarding until it is finished once (phone set up + a printer).
        val start = if (startAt == Routes.HOME && !onboarded) Routes.WELCOME else startAt

        val importLink by vm.importLink.collectAsState()
        LaunchedEffect(importLink) {
            if (importLink != null && nav.currentDestination?.route != Routes.IMPORT) nav.navigate(Routes.IMPORT)
        }

        Box {
        NavHost(
            navController = nav,
            startDestination = start,
            enterTransition = { fadeIn(tween(150)) },
            exitTransition = { fadeOut(tween(150)) }
        ) {
            composable(Routes.HOME) {  // wide: own two-pane layout
                HomeScreen(
                    vm,
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onPrinter = { id -> nav.navigate(if (id != null) Routes.printer(id) else Routes.PRINTERS) },
                    onLog = { nav.navigate(Routes.LOG) },
                    onPrint = { nav.navigate(Routes.PRINT) },
                    onScan = { nav.navigate(Routes.SCAN) },
                    onHistory = { nav.navigate(Routes.HISTORY) }
                )
            }
            page(Routes.SHARE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                ShareSettingsScreen(vm, e.arguments?.getString("id") ?: return@page, onBack = ::back)
            }
            page(Routes.DRIVER, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                DriverPickerScreen(vm, e.arguments?.getString("id") ?: return@page, onBack = ::back)
            }
            page(Routes.IMPORT) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val link by vm.importLink.collectAsState()
                ImportSettingsScreen(vm, link, onBack = ::back, onDone = { id ->
                    // From onboarding: back to its checklist (the printer step is now done).
                    if (!AppSettings.onboarded.value) nav.popBackStack(Routes.WELCOME, false)
                    else nav.navigate(Routes.printer(id)) { popUpTo(Routes.HOME) }
                })
            }
            page(Routes.WELCOME) {
                WelcomeScreen(
                    vm,
                    onImport = { nav.navigate(Routes.IMPORT) },
                    onAddPrinter = { nav.navigate(Routes.PRINTERS) },
                    onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) { inclusive = true } } }
                )
            }
            page(Routes.PRINT) {
                PrintScreen(vm, onBack = ::back, onCalibrate = { nav.navigate(Routes.calibrate(it)) },
                    onLog = { nav.navigate(Routes.LOG) })
            }
            page(Routes.CALIBRATE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                val id = e.arguments?.getString("id") ?: return@page
                CalibrationScreen(vm, id, onBack = ::back)
            }
            page(Routes.SCAN) {
                ScanScreen(vm, onBack = ::back, onPrintCopy = {
                    nav.navigate(Routes.PRINT) { popUpTo(Routes.SCAN) { inclusive = true } }
                })
            }
            page(Routes.HISTORY) { HistoryScreen(onBack = ::back) }
            page(Routes.PRINTERS) {
                PrintersScreen(
                    vm,
                    onBack = ::back,
                    onOpen = { nav.navigate(Routes.printer(it)) },
                    onAdd = { p -> nav.navigate(Routes.add(ip = p?.ip, name = p?.name, ip6 = p?.ipv6)) },
                    onImport = { nav.navigate(Routes.IMPORT) }
                )
            }
            page(Routes.PRINTER, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                val id = e.arguments?.getString("id") ?: return@page
                PrinterDetailScreen(vm, id, onBack = ::back, onEdit = { nav.navigate(Routes.add(id = it)) },
                    onShare = { nav.navigate(Routes.share(it)) },
                    onDriver = { nav.navigate(Routes.driver(it)) },
                    onCalibrate = { nav.navigate(Routes.calibrate(it)) })
            }
            page(
                Routes.ADD,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("ip") { type = NavType.StringType; defaultValue = "" },
                    navArgument("ip6") { type = NavType.StringType; defaultValue = "" },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" }
                )
            ) { e ->
                AddPrinterScreen(
                    vm,
                    editId = e.arguments?.getString("id")?.ifBlank { null },
                    prefillIp = e.arguments?.getString("ip")?.ifBlank { null },
                    prefillIp6 = e.arguments?.getString("ip6")?.ifBlank { null },
                    prefillName = e.arguments?.getString("name")?.ifBlank { null },
                    onDone = ::back
                )
            }
            page(Routes.SETTINGS) {
                SettingsScreen(onBack = ::back, onLog = { nav.navigate(Routes.LOG) },
                    onPrinters = { nav.navigate(Routes.PRINTERS) }, onHistory = { nav.navigate(Routes.HISTORY) },
                    onWelcome = { nav.navigate(Routes.WELCOME) })
            }
            page(Routes.LOG) { LogScreen(vm, onBack = ::back) }
        }
        duplexPrompt?.let { DuplexPromptOverlay(it) }
        }
    }
}
