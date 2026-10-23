package cz.ufrii.print.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import cz.ufrii.print.DuplexCalibration
import cz.ufrii.print.DuplexCalibrations
import cz.ufrii.print.DuplexGate
import cz.ufrii.print.DuplexPrompt
import cz.ufrii.print.ManualDuplexJob
import cz.ufrii.print.Notifications
import cz.ufrii.print.PaperSource
import cz.ufrii.print.R
import cz.ufrii.print.ui.AppViewModel
import cz.ufrii.print.ui.CalibState
import cz.ufrii.print.ui.components.Choice
import cz.ufrii.print.ui.components.ChoiceList
import cz.ufrii.print.ui.components.HeaderBar
import cz.ufrii.print.ui.components.OutlineButton
import cz.ufrii.print.ui.components.Overline
import cz.ufrii.print.ui.components.PaperSourcePicker
import cz.ufrii.print.ui.components.PrimaryButton
import cz.ufrii.print.ui.components.ReinsertIllustration
import cz.ufrii.print.ui.components.Section
import cz.ufrii.print.ui.components.Spinner
import cz.ufrii.print.ui.components.paperSourceName
import cz.ufrii.print.ui.theme.Ufr

/**
 * Full-screen "turn the paper" step between the two manual-duplex passes. Shown over
 * any screen while [DuplexGate] waits (in-app jobs and PrintService jobs alike).
 */
@Composable
fun DuplexPromptOverlay(p: DuplexPrompt) {
    val c = Ufr.colors
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(c.bg)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp)
    ) {
        Overline(stringResource(R.string.duplex_step2))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.duplex_turn_title), style = Ufr.type.titleM, color = c.ink)
        Text(p.title, style = Ufr.type.dataSmall, color = c.dim)
        Spacer(Modifier.height(22.dp))
        ReinsertIllustration(p.faceUp)
        Spacer(Modifier.height(22.dp))
        Text(Notifications.duplexInstruction(ctx, p), style = Ufr.type.body, color = c.ink)
        Spacer(Modifier.height(28.dp))
        PrimaryButton(stringResource(R.string.duplex_continue), { DuplexGate.resolve(p.id, true) })
        Spacer(Modifier.height(10.dp))
        OutlineButton(stringResource(R.string.duplex_cancel_back), { DuplexGate.resolve(p.id, false) })
    }
}

/**
 * Duplex calibration per printer × tray, on as few sheets as possible:
 *  1. print "1" on a blank sheet;
 *  2. re-insert it as the current guess says (printed side up/down, arrow first) and print the next number;
 *  3. the user says where it landed:
 *     - other side → the guess for the side is right; upright like a book page → save, upside down →
 *       save with the backs rotated;
 *     - same side as 1 → the guess was the wrong way round; how the number sits compared with 1
 *       also tells the rotation. The back of the same sheet is still blank, so the corrected guess is
 *       tried right away with the next number (3, 4 …) — no new sheet needed.
 * Rotation rule: the next guess rotates the backs when the number came out upside down.
 */
@Composable
fun CalibrationScreen(vm: AppViewModel, printerId: String, onBack: () -> Unit) {
    val c = Ufr.colors
    val ctx = LocalContext.current
    val state by vm.calib.collectAsState()
    var source by remember { mutableStateOf(PaperSource.CASSETTE) }
    // The setting being tested.
    var guess by remember(source) { mutableStateOf(ManualDuplexJob.defaultCalibration(source)) }
    // Highest number printed on the sheet so far (0 = blank sheet).
    var printedUpTo by remember(source) { mutableIntStateOf(0) }
    var otherSide by remember { mutableIntStateOf(-1) }
    var upright by remember { mutableIntStateOf(-1) }
    // The sheet in hand has a blank side left (the last number landed on the front).
    var sheetReused by remember(source) { mutableStateOf(false) }
    val saved = remember(source, state) { DuplexCalibrations.get(ctx, printerId, source) }

    DisposableEffect(Unit) { onDispose { vm.resetCalibration() } }
    LaunchedEffect(state) {
        (state as? CalibState.Printed)?.let { printedUpTo = it.step; otherSide = -1; upright = -1 }
    }

    fun restart() { printedUpTo = 0; sheetReused = false; otherSide = -1; upright = -1; vm.resetCalibration() }

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
    ) {
        HeaderBar(stringResource(R.string.calib_title), onBack = onBack)
        Text(stringResource(R.string.calib_intro), style = Ufr.type.bodySmall, color = c.mut)

        val printing = (state as? CalibState.Printing)?.step
        val failed = state as? CalibState.Failed
        // Waiting for answers about the number just printed (2, 3 …)?
        val asking = printing == null && failed == null && printedUpTo >= 2 && state is CalibState.Printed
        val next = printedUpTo + 1

        when {
            printing != null -> {
                Spacer(Modifier.height(30.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(20.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.calib_printing, printing), style = Ufr.type.label, color = c.ink)
                }
            }
            failed != null -> {
                Spacer(Modifier.height(20.dp))
                Text(failed.message, style = Ufr.type.data, color = c.errTx)
                Spacer(Modifier.height(16.dp))
                OutlineButton(stringResource(R.string.calib_again), { vm.resetCalibration() }, strong = true)
            }
            printedUpTo == 0 -> {
                Section(stringResource(R.string.print_source)) {
                    PaperSourcePicker(source, { source = it }, allowAuto = false,
                        available = vm.printer(printerId)?.let { cz.ufrii.print.PpdCatalog.forPrinter(ctx, it).sources }
                            ?: listOf(PaperSource.MANUAL, PaperSource.CASSETTE))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (saved != null) stringResource(R.string.calib_saved,
                        stringResource(if (saved.faceUp) R.string.duplex_face_up else R.string.duplex_face_down))
                    else stringResource(R.string.duplex_not_calibrated),
                    style = Ufr.type.dataSmall, color = c.dim
                )
                Section(stringResource(R.string.calib_step1)) {
                    Text(stringResource(R.string.calib_step1_body, paperSourceName(source)), style = Ufr.type.body, color = c.ink)
                }
                Spacer(Modifier.height(22.dp))
                PrimaryButton(stringResource(R.string.calib_print1), { vm.printCalibration(printerId, source, 1) })
            }
            !asking -> {
                // Re-insert the sheet as [guess] says and print the next number.
                // Numbers already on the sheet's printed side: "1" or "1 2".
                val onSheet = if (sheetReused) (1 until next).joinToString(" ") else "1"
                Section(stringResource(R.string.calib_step2_n, next)) {
                    ReinsertIllustration(guess.faceUp, numbers = onSheet)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(if (sheetReused) R.string.calib_do_reuse else R.string.calib_do_first,
                        onSheet,
                        stringResource(if (guess.faceUp) R.string.calib_face_up_do else R.string.calib_face_down_do),
                        paperSourceName(source), next),
                        style = Ufr.type.body, color = c.ink)
                }
                Spacer(Modifier.height(22.dp))
                PrimaryButton(stringResource(R.string.calib_print_n, next),
                    { vm.printCalibration(printerId, source, next, guess.rotate180) })
                Spacer(Modifier.height(10.dp))
                OutlineButton(stringResource(R.string.calib_new_sheet), { restart() })
            }
            else -> {
                val n = printedUpTo
                Section(stringResource(R.string.calib_q1_n, n)) {
                    ChoiceList(listOf(Choice(stringResource(R.string.calib_q1_back_n, n)),
                        Choice(stringResource(R.string.calib_q1_same_n, n))), otherSide) { otherSide = it; upright = -1 }
                }
                if (otherSide >= 0) {
                    Section(stringResource(if (otherSide == 0) R.string.calib_q2_back else R.string.calib_q2_same, n)) {
                        ChoiceList(listOf(Choice(stringResource(R.string.calib_q2_upright)),
                            Choice(stringResource(R.string.calib_q2_upside_down))), upright) { upright = it }
                    }
                }
                Spacer(Modifier.height(22.dp))
                if (otherSide >= 0 && upright >= 0) {
                    // Upside down → the backs need the opposite rotation of what was printed.
                    val rotate = guess.rotate180 xor (upright == 1)
                    if (otherSide == 0) {
                        val result = DuplexCalibration(guess.faceUp, rotate)
                        Text(stringResource(R.string.calib_result,
                            stringResource(if (result.faceUp) R.string.duplex_face_up else R.string.duplex_face_down),
                            stringResource(if (result.rotate180) R.string.calib_rot_yes else R.string.calib_rot_no)),
                            style = Ufr.type.body, color = c.ink)
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton(stringResource(R.string.action_save), {
                            vm.saveCalibration(printerId, source, result)
                            onBack()
                        })
                    } else {
                        // Same side: the sheet went in the wrong way round; its back is still blank.
                        val corrected = DuplexCalibration(!guess.faceUp, rotate)
                        Text(stringResource(R.string.calib_same_side_next), style = Ufr.type.body, color = c.ink)
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton(stringResource(R.string.calib_try_back, n + 1), {
                            guess = corrected; sheetReused = true; vm.markCalibrationReinsert()
                        })
                        Spacer(Modifier.height(10.dp))
                        OutlineButton(stringResource(R.string.calib_save_unverified), {
                            vm.saveCalibration(printerId, source, corrected)
                            onBack()
                        })
                    }
                }
            }
        }
    }
}
