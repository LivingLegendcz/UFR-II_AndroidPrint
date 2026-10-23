package cz.ufrii.print.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.ufrii.print.PaperSource
import cz.ufrii.print.R
import cz.ufrii.print.status.InputType
import cz.ufrii.print.status.PrinterStatus
import cz.ufrii.print.ui.theme.Ufr

/** One option of a [ChoiceList]. */
data class Choice(val title: String, val sub: String? = null, val enabled: Boolean = true)

/** Vertical radio cards — every option visible with a one-line explanation. */
@Composable
fun ChoiceList(options: List<Choice>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Ufr.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            val active = i == selected
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
                    .background(if (active) c.card else c.bg)
                    .border(if (active) 1.5.dp else 1.dp, if (active) c.ink else c.line, shape)
                    .clickable(enabled = o.enabled, role = Role.RadioButton) { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape).border(1.5.dp, if (active) c.ink else c.line, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (active) Box(Modifier.size(10.dp).clip(CircleShape).background(c.ink))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.title, style = Ufr.type.label.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (o.enabled) c.ink else c.dim)
                    if (o.sub != null) Text(o.sub, style = Ufr.type.labelSmall.copy(fontWeight = FontWeight.Normal), color = c.dim)
                }
            }
        }
    }
}

/** Live "paper loaded / empty" text for a tray, from the last SNMP status. */
@Composable
fun trayState(status: PrinterStatus?, type: InputType): String? {
    val input = status?.inputs?.firstOrNull { it.type == type } ?: return null
    return when (input.hasPaper) {
        true -> stringResource(R.string.input_has_paper)
        false -> stringResource(R.string.input_empty)
        null -> null
    }
}

@Composable
fun paperSourceName(s: PaperSource): String = when {
    s == PaperSource.AUTO -> stringResource(R.string.source_auto)
    s == PaperSource.MANUAL -> stringResource(R.string.input_manual)
    s == PaperSource.CASSETTE -> stringResource(R.string.input_cassette)
    s.isCassette -> stringResource(R.string.input_cassette_n, s.cassette)
    s == PaperSource.SIDE_DECK -> stringResource(R.string.input_side_deck)
    s == PaperSource.POD_DECK -> stringResource(R.string.input_pod_deck)
    else -> stringResource(R.string.input_envelope)
}

/**
 * The trays this printer model has ([available], from its PPD), with live tray state when known.
 * Without [allowAuto] (manual duplex) "Auto" is left out — the stack must go back into a known tray.
 */
@Composable
fun PaperSourcePicker(
    source: PaperSource,
    onSelect: (PaperSource) -> Unit,
    status: PrinterStatus? = null,
    allowAuto: Boolean = true,
    available: List<PaperSource> = listOf(PaperSource.AUTO, PaperSource.MANUAL, PaperSource.CASSETTE)
) {
    val values = available.filter { allowAuto || it != PaperSource.AUTO }.ifEmpty { listOf(PaperSource.AUTO) }
    fun sub(base: String, live: String?) = if (live != null) "$base · $live" else base
    val options = values.map { s ->
        when (s) {
            PaperSource.AUTO -> Choice(paperSourceName(s), stringResource(R.string.source_auto_sub))
            PaperSource.MANUAL -> Choice(paperSourceName(s),
                sub(stringResource(R.string.source_manual_sub), trayState(status, InputType.MANUAL)))
            PaperSource.CASSETTE -> Choice(paperSourceName(s),
                sub(stringResource(R.string.source_cassette_sub), trayState(status, InputType.CASSETTE)))
            else -> Choice(paperSourceName(s))
        }
    }
    ChoiceList(options, values.indexOf(source).coerceAtLeast(0)) { onSelect(values[it]) }
}

/** − n + stepper (copies). */
@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, min: Int = 1, max: Int = 99) {
    val c = Ufr.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconAction(Icons.Outlined.Remove, "−", { if (value > min) onChange(value - 1) }, tint = if (value > min) c.ink else c.line)
        Text("$value", style = Ufr.type.headline, color = c.ink, modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconAction(Icons.Outlined.Add, "+", { if (value < max) onChange(value + 1) }, tint = if (value < max) c.ink else c.line)
    }
}

/** Section label + content, used on the print / options screens. */
@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Overline(title, Modifier.padding(top = 22.dp, bottom = 10.dp))
    content()
}

/**
 * How to put the sheet back, seen from above: the printer at the top, you at the bottom, the
 * arrow on the sheet pointing into the printer. Face up shows the printed [numbers]; face down
 * shows the blank back (the numbers are underneath).
 */
@Composable
fun ReinsertIllustration(faceUp: Boolean, modifier: Modifier = Modifier, numbers: String = "1") {
    val c = Ufr.colors
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Printer
        Box(Modifier.size(width = 200.dp, height = 34.dp).clip(RoundedCornerShape(8.dp)).background(c.ink),
            contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.duplex_printer_label), style = Ufr.type.labelSmall.copy(fontWeight = FontWeight.Bold), color = c.bg)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.size(width = 150.dp, height = 200.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                drawRoundRect(if (faceUp) c.card else c.chip, Offset.Zero, size, CornerRadius(6.dp.toPx()))
                drawRoundRect(c.ink, Offset.Zero, size, CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
                // Arrow on the sheet, pointing into the printer (the top edge goes in first).
                val a = Path().apply {
                    moveTo(w / 2, 10.dp.toPx()); lineTo(w / 2 + 24.dp.toPx(), 40.dp.toPx()); lineTo(w / 2 + 9.dp.toPx(), 40.dp.toPx())
                    lineTo(w / 2 + 9.dp.toPx(), 66.dp.toPx()); lineTo(w / 2 - 9.dp.toPx(), 66.dp.toPx())
                    lineTo(w / 2 - 9.dp.toPx(), 40.dp.toPx()); lineTo(w / 2 - 24.dp.toPx(), 40.dp.toPx()); close()
                }
                if (faceUp) drawPath(a, c.acc)
                else drawPath(a, c.dim, style = Stroke(1.5.dp.toPx()))   // printed on the hidden side
            }
            Text(if (faceUp) numbers else stringResource(R.string.duplex_blank_back),
                style = if (faceUp) Ufr.type.display else Ufr.type.labelSmall,
                color = if (faceUp) c.ink else c.dim, modifier = Modifier.padding(top = 40.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.duplex_you_label), style = Ufr.type.labelSmall.copy(fontWeight = FontWeight.Bold), color = c.dim)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(if (faceUp) R.string.duplex_face_up else R.string.duplex_face_down),
            style = Ufr.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
        Text(stringResource(R.string.duplex_header_first), style = Ufr.type.labelSmall.copy(fontWeight = FontWeight.Normal),
            color = c.dim)
    }
}
