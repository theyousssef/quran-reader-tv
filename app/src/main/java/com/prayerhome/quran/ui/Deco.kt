package com.prayerhome.quran.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The designs' small SVG ornaments, drawn in their own viewBox units and scaled to the canvas.
 */

/** Two overlapping squares (one turned 45°): the Night Amber star. [inset] is the square's offset in a [box] viewBox. */
fun DrawScope.octagram(box: Float, inset: Float, stroke: Color, width: Float = 1.4f, fill: Color? = null, side: Float = size.width) {
    val k = side / box
    scale(k, k, pivot = Offset.Zero) {
        val side = box - 2 * inset
        val tl = Offset(inset, inset)
        val c = Offset(box / 2, box / 2)
        if (fill != null) {
            drawRect(fill, tl, Size(side, side))
            rotate(45f, c) { drawRect(fill, tl, Size(side, side)) }
        }
        drawRect(stroke, tl, Size(side, side), style = Stroke(width))
        rotate(45f, c) { drawRect(stroke, tl, Size(side, side), style = Stroke(width)) }
    }
}

/** A rounded square turned 45°, optionally with a smaller one inside: the Midnight Indigo diamond. */
fun DrawScope.diamond(box: Float, inset: Float, radius: Float, stroke: Color, width: Float = 1.4f, fill: Color? = null, inner: Float = 0f, innerAlpha: Float = 0.5f, side: Float = size.width) {
    val k = side / box
    scale(k, k, pivot = Offset.Zero) {
        val c = Offset(box / 2, box / 2)
        rotate(45f, c) {
            val side = box - 2 * inset
            if (fill != null) drawRoundRect(fill, Offset(inset, inset), Size(side, side), CornerRadius(radius))
            drawRoundRect(stroke, Offset(inset, inset), Size(side, side), CornerRadius(radius), style = Stroke(width))
            if (inner > 0f) {
                val s2 = box - 2 * inner
                drawRoundRect(stroke.copy(alpha = stroke.alpha * innerAlpha), Offset(inner, inner), Size(s2, s2), CornerRadius(radius * 0.66f), style = Stroke(width))
            }
        }
    }
}

/** A circle ringed with twelve dots: the Gold Leaf sun (48 viewBox). */
fun DrawScope.sunburst(stroke: Color, fill: Color?, side: Float = size.width) {
    val k = side / 48f
    scale(k, k, pivot = Offset.Zero) {
        val c = Offset(24f, 24f)
        if (fill != null) drawCircle(fill, 16f, c)
        drawCircle(stroke, 16f, c, style = Stroke(1.4f))
        for (i in 0 until 12) {
            val a = i * PI / 6
            drawCircle(stroke, 1.8f, Offset(24f + 21f * cos(a).toFloat(), 24f + 21f * sin(a).toFloat()))
        }
    }
}

/** The verse-end ornament of each theme (the number is drawn on top by the caller). */
fun DrawScope.verseMarker(deco: Deco, gold: Color, filled: Color?, side: Float) = when (deco) {
    Deco.AMBER -> octagram(50f, 10f, gold, 1.4f, filled, side)
    Deco.INDIGO -> diamond(46f, 9f, 3f, gold, 1.4f, filled, inner = 15f, side = side)
    Deco.GOLD -> sunburst(gold, filled, side)
}

/** A progress ring (Gold Leaf khatma). */
@Composable
fun Ring(size: Int, width: Float, track: Color, fill: Color, fraction: Float) {
    Canvas(Modifier.size(size.dp)) {
        val w = width * this.size.width / size
        val r = (this.size.width - w) / 2
        drawCircle(track, r, style = Stroke(w))
        drawArc(fill, -90f, 360f * fraction.coerceIn(0f, 1f), false, topLeft = Offset(w / 2, w / 2), size = Size(2 * r, 2 * r), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** The Midnight Indigo surah arch (500×150 viewBox). */
fun DrawScope.arch(fill: Color, stroke: Color, inner: Color, dot: Color) {
    val k = size.width / 500f
    scale(k, k, pivot = Offset.Zero) {
        val outer = Path().apply {
            moveTo(10f, 150f); lineTo(10f, 86f)
            cubicTo(10f, 46f, 110f, 16f, 250f, 2f)
            cubicTo(390f, 16f, 490f, 46f, 490f, 86f)
            lineTo(490f, 150f)
        }
        drawPath(outer, fill, style = Fill)
        drawPath(outer, stroke, style = Stroke(1.6f))
        val innerP = Path().apply {
            moveTo(24f, 150f); lineTo(24f, 90f)
            cubicTo(24f, 56f, 118f, 28f, 250f, 16f)
            cubicTo(382f, 28f, 476f, 56f, 476f, 90f)
            lineTo(476f, 150f)
        }
        drawPath(innerP, inner, style = Stroke(1f))
        drawCircle(dot, 4f, Offset(250f, 34f))
    }
}

/** The Gold Leaf surah medallion (116 viewBox). */
fun DrawScope.medallion(bg: Color, ring: Color, star: Color, center: Color) {
    val k = size.width / 116f
    scale(k, k, pivot = Offset.Zero) {
        val c = Offset(58f, 58f)
        drawCircle(bg, 55f, c)
        drawCircle(ring, 55f, c, style = Stroke(1.2f))
        for ((deg, alpha) in listOf(0f to 1f, 45f to 1f, 22.5f to 0.55f, 67.5f to 0.55f)) {
            rotate(deg, c) { drawRect(star.copy(alpha = alpha), Offset(22f, 22f), Size(72f, 72f), style = Stroke(1.3f)) }
        }
        drawCircle(center, 27f, c)
        drawCircle(star, 27f, c, style = Stroke(1.2f))
    }
}
