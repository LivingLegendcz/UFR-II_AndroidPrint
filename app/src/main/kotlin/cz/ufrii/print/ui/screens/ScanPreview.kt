package cz.ufrii.print.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import cz.ufrii.print.AreaPreset
import cz.ufrii.print.R
import cz.ufrii.print.ScanArea
import cz.ufrii.print.ui.theme.Ufr

/** Selected part of the glass as fractions of the preview (0..1), left/top/right/bottom. */
data class AreaSel(val l: Float, val t: Float, val r: Float, val b: Float) {
    val isFull: Boolean get() = l <= 0.005f && t <= 0.005f && r >= 0.995f && b >= 0.995f

    /** In mm on a glass of [glassW] × [glassH] mm, as the scanner's -l -t -x -y expect. */
    fun toArea(glassW: Float, glassH: Float) = ScanArea(l * glassW, t * glassH, (r - l) * glassW, (b - t) * glassH)

    companion object {
        val FULL = AreaSel(0f, 0f, 1f, 1f)
        fun of(box: FloatArray?) = box?.let { AreaSel(it[0], it[1], it[2], it[3]) } ?: FULL
        fun of(a: ScanArea?, glassW: Float, glassH: Float) = a?.let {
            AreaSel(it.left / glassW, it.top / glassH, ((it.left + it.width) / glassW).coerceAtMost(1f),
                ((it.top + it.height) / glassH).coerceAtMost(1f))
        } ?: FULL
    }
}

private enum class Handle { TL, TR, BL, BR, MOVE, NEW }

/** Smallest frame side, as a fraction of the glass (~6 mm). */
private const val MIN_SIDE = 0.03f

/**
 * The preview with a frame to drag: corners resize, inside moves, outside draws a new frame.
 * Everything outside the frame is dimmed — that part will not be scanned.
 */
@Composable
fun CropBox(bitmap: Bitmap, sel: AreaSel, onChange: (AreaSel) -> Unit) {
    val c = Ufr.colors
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val current by rememberUpdatedState(sel)
    val change by rememberUpdatedState(onChange)
    val grab = with(LocalDensity.current) { 32.dp.toPx() }
    val shape = RoundedCornerShape(12.dp)

    Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height).clip(shape).border(1.dp, c.line, shape)) {
        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            var handle = Handle.NEW
            var anchor = Offset.Zero
            var before = AreaSel.FULL
            detectDragGestures(
                onDragStart = { p ->
                    val s = current
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val corners = listOf(
                        Handle.TL to Offset(s.l * w, s.t * h), Handle.TR to Offset(s.r * w, s.t * h),
                        Handle.BL to Offset(s.l * w, s.b * h), Handle.BR to Offset(s.r * w, s.b * h))
                    val near = corners.minBy { (it.second - p).getDistance() }
                    handle = when {
                        (near.second - p).getDistance() <= grab -> near.first
                        p.x in s.l * w..s.r * w && p.y in s.t * h..s.b * h -> Handle.MOVE
                        else -> Handle.NEW
                    }
                    anchor = Offset(p.x / w, p.y / h)
                    before = s
                },
                onDragEnd = {
                    val s = current
                    if (s.r - s.l < MIN_SIDE || s.b - s.t < MIN_SIDE) change(before)
                },
                onDrag = { ch, d ->
                    ch.consume()
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val dx = d.x / w; val dy = d.y / h
                    val s = current
                    change(when (handle) {
                        Handle.MOVE -> {
                            val mx = dx.coerceIn(-s.l, 1f - s.r); val my = dy.coerceIn(-s.t, 1f - s.b)
                            AreaSel(s.l + mx, s.t + my, s.r + mx, s.b + my)
                        }
                        Handle.TL -> s.copy(l = (s.l + dx).coerceIn(0f, s.r - MIN_SIDE), t = (s.t + dy).coerceIn(0f, s.b - MIN_SIDE))
                        Handle.TR -> s.copy(r = (s.r + dx).coerceIn(s.l + MIN_SIDE, 1f), t = (s.t + dy).coerceIn(0f, s.b - MIN_SIDE))
                        Handle.BL -> s.copy(l = (s.l + dx).coerceIn(0f, s.r - MIN_SIDE), b = (s.b + dy).coerceIn(s.t + MIN_SIDE, 1f))
                        Handle.BR -> s.copy(r = (s.r + dx).coerceIn(s.l + MIN_SIDE, 1f), b = (s.b + dy).coerceIn(s.t + MIN_SIDE, 1f))
                        Handle.NEW -> {
                            val px = (ch.position.x / w).coerceIn(0f, 1f); val py = (ch.position.y / h).coerceIn(0f, 1f)
                            AreaSel(minOf(anchor.x, px), minOf(anchor.y, py), maxOf(anchor.x, px), maxOf(anchor.y, py))
                        }
                    })
                }
            )
        }) {
            val w = size.width; val h = size.height
            val l = sel.l * w; val t = sel.t * h; val r = sel.r * w; val b = sel.b * h
            val shade = Color.Black.copy(alpha = 0.45f)
            drawRect(shade, Offset.Zero, Size(w, t))
            drawRect(shade, Offset(0f, b), Size(w, h - b))
            drawRect(shade, Offset(0f, t), Size(l, b - t))
            drawRect(shade, Offset(r, t), Size(w - r, b - t))
            drawRect(c.acc, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
            for (p in listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))) {
                drawCircle(Color.White, 8.dp.toPx(), p)
                drawCircle(c.acc, 6.dp.toPx(), p)
            }
        }
    }
}

/** "celé sklo" / "A5" / "100 × 150 mm (od 10, 20)". */
@Composable
fun areaPresetLabel(p: AreaPreset): String = when {
    p.custom != null -> stringResource(R.string.scan_area_custom_label, p.custom.width.toInt(), p.custom.height.toInt(),
        p.custom.left.toInt(), p.custom.top.toInt())
    p.format != null -> p.format.label
    else -> stringResource(R.string.scan_area_full_lower)
}
