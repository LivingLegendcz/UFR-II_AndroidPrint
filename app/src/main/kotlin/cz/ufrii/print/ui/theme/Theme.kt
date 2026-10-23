package cz.ufrii.print.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cz.ufrii.print.R

// -----------------------------------------------------------------------------
// Colour tokens — docs/design "UFR-II Design Spec" §2 + Prototype v1 extras
// (chip, sw, logo, errtx, invLine). Names follow the spec.
// -----------------------------------------------------------------------------

@Immutable
data class UfrColors(
    val bg: Color, val card: Color, val line: Color, val line2: Color,
    val ink: Color, val mut: Color, val dim: Color,
    val acc: Color, val onAcc: Color, val accSub: Color, val link: Color,
    val inv: Color, val invInk: Color, val invMut: Color, val invLine: Color,
    val ok: Color, val err: Color, val errBg: Color, val errLine: Color, val errTx: Color,
    val chip: Color, val sw: Color, val logo: Color,
    val isDark: Boolean
)

/** Fixed colours (same in both themes). */
object Fixed {
    val tonerC = Color(0xFF3AC3E0)
    val tonerM = Color(0xFFE255A1)
    val tonerY = Color(0xFFE8C547)
    val tonerK = Color(0xFFF7F4EC)       // "black" bar on the dark hero card
    val stateReady = Color(0xFF8FD3A6)
    val stateReadyDot = Color(0xFF4FBF77)
    val stateWarn = Color(0xFFE8C547)
    val statePaper = Color(0xFFF0A868)
    val stateDown = Color(0xFFE58C7A)
    val console = Color(0xFF16150F)
    val consoleText = Color(0xFFC9C3B2)
    val consoleTime = Color(0xFF8B8677)
    val consoleOk = Color(0xFF8FD3A6)
    val consoleAcc = Color(0xFFE8C547)
    val consoleErr = Color(0xFFE58C7A)
}

val LightUfr = UfrColors(
    bg = Color(0xFFF7F4EC), card = Color(0xFFFFFFFF), line = Color(0xFFE3DED1), line2 = Color(0xFFEFEBE0),
    ink = Color(0xFF16150F), mut = Color(0xFF6B675C), dim = Color(0xFF8B8677),
    acc = Color(0xFFE8C547), onAcc = Color(0xFF16150F), accSub = Color(0xFF6B5A12), link = Color(0xFF8A6A00),
    inv = Color(0xFF16150F), invInk = Color(0xFFF7F4EC), invMut = Color(0xFFA5A093), invLine = Color(0xFF16150F),
    ok = Color(0xFF2E7D4F), err = Color(0xFFC8452F), errBg = Color(0xFFFBEDE9), errLine = Color(0xFFF0CFC6),
    errTx = Color(0xFF5C4B46),
    chip = Color(0xFFEDEAE1), sw = Color(0xFF16150F), logo = Color(0xFF16150F),
    isDark = false
)

val DarkUfr = UfrColors(
    bg = Color(0xFF16150F), card = Color(0xFF221F17), line = Color(0xFF322E24), line2 = Color(0xFF2A2720),
    ink = Color(0xFFF7F4EC), mut = Color(0xFFA5A093), dim = Color(0xFF8B8677),
    acc = Color(0xFFE8C547), onAcc = Color(0xFF16150F), accSub = Color(0xFF6B5A12), link = Color(0xFFE8C547),
    inv = Color(0xFF221F17), invInk = Color(0xFFF7F4EC), invMut = Color(0xFFA5A093), invLine = Color(0xFF322E24),
    ok = Color(0xFF4FBF77), err = Color(0xFFE58C7A), errBg = Color(0xFF2A1A16), errLine = Color(0xFF4A2A22),
    errTx = Color(0xFFC3AFA9),
    chip = Color(0xFF221F17), sw = Color(0xFF4A4437), logo = Color(0xFFE8C547),
    isDark = true
)

// -----------------------------------------------------------------------------
// Typography — Space Grotesk for people, JetBrains Mono for machine values (§3)
// -----------------------------------------------------------------------------

@OptIn(ExperimentalTextApi::class)
private fun grotesk(w: Int) = Font(
    R.font.space_grotesk, FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w))
)
@OptIn(ExperimentalTextApi::class)
private fun mono(w: Int) = Font(
    R.font.jetbrains_mono, FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w))
)

val Grotesk = FontFamily(grotesk(400), grotesk(500), grotesk(600), grotesk(700))
val Mono = FontFamily(mono(400), mono(500), mono(600), mono(700))

@Immutable
data class UfrType(
    val display: TextStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.025).em),
    val titleL: TextStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 33.sp, letterSpacing = (-0.02).em),
    val titleM: TextStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em),
    val headline: TextStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp),
    val header: TextStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    val body: TextStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp),
    val bodySmall: TextStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    val label: TextStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 18.sp),
    val labelSmall: TextStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    val overline: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.1.em),
    val logo: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.14.em),
    val data: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    val dataSmall: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 16.sp),
    val console: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 21.sp),
)

// -----------------------------------------------------------------------------
// Theme
// -----------------------------------------------------------------------------

enum class Appearance { SYSTEM, LIGHT, DARK }

val LocalUfrColors = staticCompositionLocalOf { LightUfr }
val LocalUfrType = staticCompositionLocalOf { UfrType() }

object Ufr {
    val colors: UfrColors @Composable get() = LocalUfrColors.current
    val type: UfrType @Composable get() = LocalUfrType.current
}

@Composable
fun UfrTheme(appearance: Appearance = Appearance.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    val c = if (dark) DarkUfr else LightUfr
    // Material3 only for the few stock widgets we still use (text selection, ripples, dialogs).
    val scheme = if (dark) {
        darkColorScheme(primary = c.acc, onPrimary = c.onAcc, background = c.bg, surface = c.card,
            onBackground = c.ink, onSurface = c.ink, error = c.err, outline = c.line)
    } else {
        lightColorScheme(primary = c.acc, onPrimary = c.onAcc, background = c.bg, surface = c.card,
            onBackground = c.ink, onSurface = c.ink, error = c.err, outline = c.line)
    }
    CompositionLocalProvider(LocalUfrColors provides c, LocalUfrType provides UfrType()) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
