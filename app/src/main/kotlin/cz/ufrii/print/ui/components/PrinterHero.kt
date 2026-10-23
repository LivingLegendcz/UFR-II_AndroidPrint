package cz.ufrii.print.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cz.ufrii.print.Printer
import cz.ufrii.print.R
import cz.ufrii.print.status.AlertKind
import cz.ufrii.print.status.PrinterStatus
import cz.ufrii.print.status.Toner
import cz.ufrii.print.status.TonerColor
import cz.ufrii.print.ui.theme.Fixed
import cz.ufrii.print.ui.theme.Ufr

/** Low-toner threshold for the hero state and (later) notifications. */
const val TONER_LOW_PERCENT = 15

enum class HeroState { LOADING, READY, TONER_LOW, PAPER, COVER, JAM, DOWN }

/** Derives the hero card state from the last SNMP status (null = not read yet). */
fun heroStateOf(status: PrinterStatus?, loading: Boolean): HeroState {
    if (status == null) return if (loading) HeroState.LOADING else HeroState.DOWN
    if (!status.reachable) return HeroState.DOWN
    val kinds = status.alerts.map { it.kind }.toSet()
    return when {
        AlertKind.COVER_OPEN in kinds -> HeroState.COVER
        AlertKind.JAM in kinds -> HeroState.JAM
        AlertKind.PAPER_EMPTY in kinds || AlertKind.TRAY_MISSING in kinds -> HeroState.PAPER
        AlertKind.TONER_EMPTY in kinds || AlertKind.TONER_LOW in kinds ||
            status.toners.any { (it.levelPercent ?: 100) <= TONER_LOW_PERCENT } -> HeroState.TONER_LOW
        else -> HeroState.READY
    }
}

@Composable
fun heroLabel(s: HeroState): Pair<String, Color> = when (s) {
    HeroState.LOADING -> stringResource(R.string.state_loading) to Ufr.colors.invMut
    HeroState.READY -> stringResource(R.string.state_ready) to Fixed.stateReady
    HeroState.TONER_LOW -> stringResource(R.string.state_toner_low) to Fixed.stateWarn
    HeroState.PAPER -> stringResource(R.string.state_paper) to Fixed.statePaper
    HeroState.COVER -> stringResource(R.string.state_cover) to Fixed.stateDown
    HeroState.JAM -> stringResource(R.string.state_jam) to Fixed.stateDown
    HeroState.DOWN -> stringResource(R.string.state_down) to Fixed.stateDown
}

@Composable
fun tonerName(c: TonerColor): String = stringResource(
    when (c) {
        TonerColor.BLACK -> R.string.toner_black
        TonerColor.CYAN -> R.string.toner_cyan
        TonerColor.MAGENTA -> R.string.toner_magenta
        TonerColor.YELLOW -> R.string.toner_yellow
        TonerColor.OTHER -> R.string.toner_other
    }
)

/** One-line explanation under the address (spec: "Žlutý toner na 8 %. Černobílý tisk funguje dál."). */
@Composable
fun heroHint(s: HeroState, status: PrinterStatus?): String? = when (s) {
    HeroState.TONER_LOW -> status?.toners?.filter { it.levelPercent != null }?.minByOrNull { it.levelPercent!! }?.let {
        stringResource(R.string.hint_toner_low, tonerName(it.color), it.levelPercent!!)
    }
    HeroState.PAPER -> stringResource(R.string.hint_paper)
    HeroState.COVER -> stringResource(R.string.hint_cover)
    HeroState.JAM -> stringResource(R.string.hint_jam)
    HeroState.DOWN -> if (status == null) null else stringResource(R.string.hint_down)
    else -> null
}

fun tonerColorValue(c: TonerColor, onDark: Boolean = true): Color = when (c) {
    TonerColor.CYAN -> Fixed.tonerC
    TonerColor.MAGENTA -> Fixed.tonerM
    TonerColor.YELLOW -> Fixed.tonerY
    TonerColor.BLACK -> if (onDark) Fixed.tonerK else Color(0xFF16150F)
    TonerColor.OTHER -> Color(0xFF8B8677)
}

/** Toners in the CMYK order of the design strip (SNMP reports K,Y,M,C). */
fun cmykOrder(toners: List<Toner>): List<Toner> {
    val order = listOf(TonerColor.CYAN, TonerColor.MAGENTA, TonerColor.YELLOW, TonerColor.BLACK, TonerColor.OTHER)
    return toners.sortedBy { order.indexOf(it.color) }
}

/**
 * Hero printer card (§4/§5): inverse card r24, state dot + label, two-line name,
 * mono address, hint, CMYK strip that shows real toner levels when known.
 */
@Composable
fun PrinterHero(
    printer: Printer?,
    status: PrinterStatus?,
    loading: Boolean,
    onClick: () -> Unit
) {
    val c = Ufr.colors
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(c.inv).border(1.dp, c.invLine, shape)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        HeroGlow(Modifier.align(Alignment.TopEnd))
        Column(Modifier.padding(22.dp)) {
            if (printer == null) {
                Text(stringResource(R.string.hero_no_printer), style = Ufr.type.titleM, color = c.invInk)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.hero_no_printer_hint), style = Ufr.type.data, color = c.invMut)
                return@Column
            }
            val state = heroStateOf(status, loading)
            val (label, labelColor) = heroLabel(state)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state == HeroState.LOADING) Spinner(size = 9.dp, color = labelColor, stroke = 1.5.dp)
                else StatusDot(if (state == HeroState.READY) Fixed.stateReadyDot else labelColor)
                Spacer(Modifier.width(8.dp))
                Text(label.uppercase(), style = Ufr.type.overline.copy(letterSpacing = Ufr.type.overline.letterSpacing * 0.8f),
                    color = labelColor)
            }
            Spacer(Modifier.height(14.dp))
            Text(heroName(printer), style = Ufr.type.titleM, color = c.invInk)
            Spacer(Modifier.height(6.dp))
            Text("${printer.ip}:${printer.port}", style = Ufr.type.data, color = c.invMut)
            heroHint(state, status)?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = Ufr.type.bodySmall, color = c.invInk)
            }
            Spacer(Modifier.height(20.dp))
            TonerStrip(status?.toners.orEmpty())
        }
    }
}

/** "Canon MF8030Cn (default)" → "Canon\nMF8030Cn" like the prototype's two-line title. */
fun heroName(p: Printer): String {
    val n = p.name.replace(Regex("\\s*\\(default\\)"), "").trim()
    val i = n.indexOf(' ')
    return if (i in 1 until n.length - 1) n.substring(0, i) + "\n" + n.substring(i + 1) else n
}

/** 4 × 5 dp bars; filled to the toner level when known, full decorative strip otherwise. */
@Composable
fun TonerStrip(toners: List<Toner>) {
    val bars = if (toners.isEmpty()) {
        listOf(TonerColor.CYAN, TonerColor.MAGENTA, TonerColor.YELLOW, TonerColor.BLACK).map { it to 1f }
    } else {
        cmykOrder(toners).map { it.color to ((it.levelPercent ?: 100) / 100f) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        bars.forEach { (color, f) ->
            Box(Modifier.weight(1f)) {
                LevelBar(f, tonerColorValue(color), track = Color.White.copy(alpha = 0.12f), height = 5.dp)
            }
        }
    }
}
