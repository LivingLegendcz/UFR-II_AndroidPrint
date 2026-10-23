package cz.ufrii.print.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.ufrii.print.ColorMode
import cz.ufrii.print.R
import cz.ufrii.print.ui.theme.Ufr

// -----------------------------------------------------------------------------
// Text atoms
// -----------------------------------------------------------------------------

@Composable
fun Logo(modifier: Modifier = Modifier) {
    Text("UFR·II", style = Ufr.type.logo, color = Ufr.colors.logo, modifier = modifier)
}

/** Mono 11, +10 % tracking, upper case — section labels and states. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = Ufr.colors.dim) {
    Text(text.uppercase(), style = Ufr.type.overline, color = color, modifier = modifier)
}

// -----------------------------------------------------------------------------
// Header
// -----------------------------------------------------------------------------

@Composable
fun HeaderBar(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector = Icons.AutoMirrored.Outlined.ArrowBack,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconAction(backIcon, stringResource(R.string.cd_back), onBack, size = 22.dp, tint = Ufr.colors.ink)
            Spacer(Modifier.width(6.dp))
        }
        if (title != null) {
            Text(title, style = Ufr.type.header, color = Ufr.colors.ink, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

/** 48 dp touch target around a small outlined icon (a11y §9). */
@Composable
fun IconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Dp = 22.dp,
    tint: Color = Ufr.colors.dim
) {
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size))
    }
}

// -----------------------------------------------------------------------------
// Buttons (§4: primary 56–58 pill acc, secondary outline, destructive = outline+err)
// -----------------------------------------------------------------------------

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 58.dp
) {
    val c = Ufr.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = height).clip(RoundedCornerShape(100.dp))
            .background(if (enabled) c.acc else c.line)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.onAcc, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = Ufr.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.onAcc)
    }
}

@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    strong: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 52.dp
) {
    val c = Ufr.colors
    val fg = when {
        destructive -> c.err
        strong -> c.ink
        else -> c.mut
    }
    val border = when {
        destructive -> c.err
        strong -> c.ink
        else -> c.line
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = height).clip(RoundedCornerShape(100.dp))
            .border(1.dp, border, RoundedCornerShape(100.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Ufr.type.label, color = fg)
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ufr.colors.dim) {
    Box(
        modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(100.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = Ufr.type.label, color = color)
    }
}

// -----------------------------------------------------------------------------
// Cards & lists
// -----------------------------------------------------------------------------

@Composable
fun UfrCard(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = Ufr.colors
    val shape = RoundedCornerShape(radius)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.line, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content
    )
}

/** One row of a list block: title (+subtitle) on the left, trailing content on the right. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = Ufr.colors.dim,
    subtitleMono: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = Ufr.colors.ink,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val c = Ufr.colors
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = Ufr.type.label, color = titleColor)
                if (subtitle != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        subtitle,
                        style = if (subtitleMono) Ufr.type.dataSmall else Ufr.type.labelSmall.copy(fontWeight = FontWeight.Normal),
                        color = subtitleColor
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line2))
    }
}

@Composable
fun ValueText(text: String, mono: Boolean = false) {
    Text(text, style = if (mono) Ufr.type.data else Ufr.type.labelSmall.copy(fontWeight = FontWeight.Normal),
        color = Ufr.colors.mut)
}

/** Dashed empty-state block (§4). */
@Composable
fun EmptyBlock(text: String, modifier: Modifier = Modifier) {
    val c = Ufr.colors
    Box(
        modifier.fillMaxWidth().heightIn(min = 72.dp).drawBehind {
            drawRoundRect(
                color = c.line,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            )
        },
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = Ufr.type.bodySmall, color = c.dim, textAlign = TextAlign.Center,
            modifier = Modifier.padding(18.dp))
    }
}

// -----------------------------------------------------------------------------
// Mode segment Barevně / Černobíle (§4)
// -----------------------------------------------------------------------------

@Composable
fun ModeSegment(
    mode: ColorMode,
    onSelect: (ColorMode) -> Unit,
    colorAvailable: Boolean = true,
    vertical: Boolean = false
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ModeTile(stringResource(R.string.mode_color), Icons.Outlined.Palette,
            mode == ColorMode.COLOR, colorAvailable, vertical) { onSelect(ColorMode.COLOR) }
        ModeTile(stringResource(R.string.mode_mono), Icons.Outlined.Contrast,
            mode == ColorMode.MONO, true, vertical) { onSelect(ColorMode.MONO) }
    }
}

@Composable
private fun RowScope.ModeTile(
    text: String, icon: ImageVector, active: Boolean, enabled: Boolean, vertical: Boolean, onClick: () -> Unit
) {
    val c = Ufr.colors
    val shape = RoundedCornerShape(16.dp)
    val bg = if (active) c.ink else c.card
    val fg = if (active) c.bg else c.mut
    val mod = Modifier.weight(1f).heightIn(min = 52.dp).clip(shape).background(bg)
        .then(if (active) Modifier else Modifier.border(1.dp, c.line, shape))
        .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .alpha(if (enabled) 1f else 0.4f)
        .padding(if (vertical) PaddingValues(14.dp) else PaddingValues(horizontal = 16.dp, vertical = 14.dp))
    val weight = if (active) FontWeight.SemiBold else FontWeight.Medium
    if (vertical) {
        Column(mod, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Text(text, style = Ufr.type.label.copy(fontSize = Ufr.type.labelSmall.fontSize * 1.17f, fontWeight = weight), color = fg)
        }
    } else {
        Row(mod, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = Ufr.type.label.copy(fontSize = Ufr.type.labelSmall.fontSize * 1.17f, fontWeight = weight), color = fg)
        }
    }
}

// -----------------------------------------------------------------------------
// Switch 46×26 (§4), status dot, spinner
// -----------------------------------------------------------------------------

@Composable
fun UfrSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, description: String) {
    val c = Ufr.colors
    Box(
        Modifier.size(width = 46.dp, height = 26.dp).clip(RoundedCornerShape(100.dp))
            .background(if (checked) c.sw else c.line)
            .then(if (onChange != null) Modifier.clickable(role = Role.Switch) { onChange(!checked) } else Modifier)
            .semantics { contentDescription = description }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(if (checked) c.acc else c.card))
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 7.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** 1 s linear spinner (§3 motion). */
@Composable
fun Spinner(size: Dp = 14.dp, color: Color = Ufr.colors.acc, stroke: Dp = 2.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Canvas(Modifier.size(size).rotate(angle)) {
        drawArc(color, startAngle = 0f, sweepAngle = 270f, useCenter = false,
            style = Stroke(width = stroke.toPx()))
    }
}

/** Horizontal progress bar 8 dp (boot / toner level). */
@Composable
fun LevelBar(fraction: Float?, color: Color, track: Color = Ufr.colors.line, height: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(track)) {
        if (fraction != null) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height)
                .clip(RoundedCornerShape(height / 2)).background(color))
        }
    }
}

// -----------------------------------------------------------------------------
// Decorative helper used by the hero card
// -----------------------------------------------------------------------------

@Composable
fun HeroGlow(modifier: Modifier = Modifier) {
    Canvas(modifier.size(150.dp).offset(x = 30.dp, y = (-30).dp)) {
        drawCircle(Color(0xFFE8C547).copy(alpha = 0.12f), radius = size.minDimension / 2,
            center = Offset(size.width / 2, size.height / 2))
    }
}

@Composable
fun outlineBorder(): BorderStroke = BorderStroke(1.dp, Ufr.colors.line)
