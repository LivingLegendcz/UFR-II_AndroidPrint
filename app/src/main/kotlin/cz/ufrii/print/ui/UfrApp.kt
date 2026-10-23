package cz.ufrii.print.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cz.ufrii.print.AppSettings
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
    const val ADD = "add?id={id}&ip={ip}&name={name}"
    const val SETTINGS = "settings"
    const val LOG = "log"

    fun printer(id: String) = "printer/$id"
    fun add(id: String? = null, ip: String? = null, name: String? = null): String {
        fun e(s: String?) = URLEncoder.encode(s ?: "", "UTF-8")
        return "add?id=${e(id)}&ip=${e(ip)}&name=${e(name)}"
    }
}

/**
 * App root: theme + navigation. [startAt] is HOME for the launcher and PRINTERS when
 * the system Print settings open AddPrinterActivity; [onExit] closes the host activity
 * when back is pressed on the start destination.
 */
@Composable
fun UfrApp(vm: AppViewModel, startAt: String = Routes.HOME, onExit: () -> Unit) {
    val appearance by AppSettings.appearance.collectAsState()
    UfrTheme(appearance) {
        val nav = rememberNavController()
        fun back() { if (!nav.popBackStack()) onExit() }

        NavHost(
            navController = nav,
            startDestination = startAt,
            enterTransition = { fadeIn(tween(150)) },
            exitTransition = { fadeOut(tween(150)) }
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    vm,
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onPrinter = { id -> nav.navigate(if (id != null) Routes.printer(id) else Routes.PRINTERS) },
                    onLog = { nav.navigate(Routes.LOG) }
                )
            }
            composable(Routes.PRINTERS) {
                PrintersScreen(
                    vm,
                    onBack = ::back,
                    onOpen = { nav.navigate(Routes.printer(it)) },
                    onAdd = { ip, name -> nav.navigate(Routes.add(ip = ip, name = name)) }
                )
            }
            composable(Routes.PRINTER, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                val id = e.arguments?.getString("id") ?: return@composable
                PrinterDetailScreen(vm, id, onBack = ::back, onEdit = { nav.navigate(Routes.add(id = it)) })
            }
            composable(
                Routes.ADD,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("ip") { type = NavType.StringType; defaultValue = "" },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" }
                )
            ) { e ->
                AddPrinterScreen(
                    vm,
                    editId = e.arguments?.getString("id")?.ifBlank { null },
                    prefillIp = e.arguments?.getString("ip")?.ifBlank { null },
                    prefillName = e.arguments?.getString("name")?.ifBlank { null },
                    onDone = ::back
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = ::back, onLog = { nav.navigate(Routes.LOG) },
                    onPrinters = { nav.navigate(Routes.PRINTERS) })
            }
            composable(Routes.LOG) { LogScreen(vm, onBack = ::back) }
        }
    }
}
