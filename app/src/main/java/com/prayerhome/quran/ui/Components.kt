package com.prayerhome.quran.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- text

/** Latin text. Sizes are in design pixels (the UI is laid out on a 1920×1080 canvas). */
@Composable
fun T(
    text: String,
    font: FontFamily,
    size: Int,
    color: Color,
    modifier: Modifier = Modifier,
    weight: Int = 400,
    spacing: Float = 0f,
    lineHeight: Float = 0f,
    align: TextAlign = TextAlign.Unspecified,
    italic: Boolean = false,
    maxLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = if (maxLines == 1) modifier.lineBox(size, lineHeight) else modifier,
        style = style(font, size, color, weight, spacing, if (maxLines == 1) 0f else lineHeight, align, italic),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        softWrap = maxLines > 1,
    )
}

@Composable
fun T(
    text: AnnotatedString,
    font: FontFamily,
    size: Int,
    color: Color,
    modifier: Modifier = Modifier,
    weight: Int = 400,
    spacing: Float = 0f,
    align: TextAlign = TextAlign.Unspecified,
) {
    BasicText(text, modifier, style(font, size, color, weight, spacing, 0f, align, false), maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
}

/** Arabic text (single line unless [maxLines] > 1): right-to-left shaping with an Arabic face. */
@Composable
fun Ar(
    text: String,
    font: FontFamily,
    size: Int,
    color: Color,
    modifier: Modifier = Modifier,
    weight: Int = 400,
    lineHeight: Float = 0f,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = 1,
) {
    BasicText(
        // No-break spaces keep Android from shaping each word on its own, which detaches
        // the swash tails some Arabic faces (Amiri's ى) hang into the following space.
        text = if (maxLines == 1) text.replace(' ', '\u00A0') else text,
        modifier = modifier.lineBox(size, if (maxLines == 1) lineHeight else 0f),
        style = style(font, size, color, weight, 0f, if (maxLines == 1) 0f else lineHeight, align, false).copy(textDirection = TextDirection.Rtl),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        softWrap = maxLines > 1,
    )
}

/**
 * CSS-style line-height for single-line text: the box is exactly size × lineHeight tall and the glyphs
 * are centred in it, overflowing when the font is taller (Compose otherwise keeps the font's own height).
 */
fun Modifier.lineBox(size: Int, lineHeight: Float): Modifier =
    if (lineHeight <= 0f) this else this.height((size * lineHeight).dp).wrapContentHeight(Alignment.CenterVertically, unbounded = true)

fun style(font: FontFamily, size: Int, color: Color, weight: Int, spacing: Float, lineHeight: Float, align: TextAlign, italic: Boolean) = TextStyle(
    fontFamily = font,
    fontSize = size.sp,
    color = color,
    fontWeight = FontWeight(weight),
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    letterSpacing = if (spacing != 0f) spacing.em else TextUnit.Unspecified,
    lineHeight = if (lineHeight > 0f) (size * lineHeight).sp else TextUnit.Unspecified,
    textAlign = align,
)

fun hex(v: Long) = Color(0xFF000000 or v)
fun hexA(v: Long) = Color(v)

// ---------------------------------------------------------------- focus

/** True inside a theme thumbnail: its tiles draw but never take focus. */
val LocalNoFocus = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * A D-pad target. OK/Enter clicks; holding OK for half a second fires [onLongClick] once.
 * [content] receives whether this item currently has focus.
 */
@Composable
fun Focusable(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    // Track real focus state (not interactions): it stays correct when focus is set before the window has it.
    var focused by remember { mutableStateOf(false) }
    val longFired = remember { booleanArrayOf(false) }
    Box(
        modifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onPreviewKeyEvent { e ->
                val code = e.key.nativeKeyCode
                if (code != AndroidKeyEvent.KEYCODE_DPAD_CENTER && code != AndroidKeyEvent.KEYCODE_ENTER && code != AndroidKeyEvent.KEYCODE_NUMPAD_ENTER) {
                    return@onPreviewKeyEvent false
                }
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        val repeat = e.nativeKeyEvent.repeatCount
                        if (repeat == 0) longFired[0] = false
                        if (onLongClick != null && !longFired[0] && (e.nativeKeyEvent.isLongPress || repeat >= 10)) {
                            longFired[0] = true
                            onLongClick()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (!longFired[0]) onClick()
                        longFired[0] = false
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .let { if (LocalNoFocus.current) it else it.focusable() },
    ) { content(focused) }
}

/** Border + background helper mirroring the designs' CSS boxes. */
fun Modifier.box(bg: Color, border: Color, width: Dp, shape: Shape): Modifier =
    this.clip(shape).background(bg, shape).border(width, border, shape)

// ---------------------------------------------------------------- icons

/** Stroke icons from the designs (24×24 SVG paths). */
object Ico {
    const val PANEL_CLOSE = "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M9 4v16 M16 9l-3 3 3 3"
    const val PANEL_OPEN = "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M9 4v16 M13 9l3 3-3 3"
    const val LIST = "M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01"
    const val LAYERS = "M12 3l9 5-9 5-9-5z M3 13l9 5 9-5"
    const val BOOKMARK = "M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"
    const val HOME = "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z"
    const val CHEVRON_RIGHT = "M9 18l6-6-6-6"
    const val CHEVRON_UP = "M6 15l6-6 6 6"
    const val CHECK = "M5 12l5 5 9-10"
    const val REPEAT = "M17 2l4 4-4 4 M3 11V9a3 3 0 0 1 3-3h15 M7 22l-4-4 4-4 M21 13v2a3 3 0 0 1-3 3H3"
    const val PALETTE = "M12 3a9 9 0 1 0 0 18c1.1 0 1.8-.8 1.8-1.7 0-.5-.2-.9-.5-1.2-.3-.3-.5-.7-.5-1.2 0-1 .8-1.7 1.8-1.7H17a4 4 0 0 0 4-4c0-4.6-4-8.2-9-8.2z M7.5 11.5h.01 M10.5 7.5h.01 M15 7.5h.01"
    const val MIC = "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M19 11a7 7 0 0 1-14 0 M12 18v3"
    // filled glyphs
    const val PLAY = "M8 5l12 7-12 7z"
    const val PAUSE = "M7 5h3a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z M15 5h3a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z"
    const val PREV = "M18 19L8 12l10-7z M6 5h1.5a1 1 0 0 1 0 0v14H6a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z"
    const val NEXT = "M6 5l10 7-10 7z M16.5 5H18a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-1.5z"
}

private val pathCache = HashMap<String, androidx.compose.ui.graphics.Path>()

fun iconPath(path: String) = pathCache.getOrPut(path) { PathParser().parsePathString(path).toPath() }

/** A 24×24 stroke icon scaled to [size]. */
@Composable
fun Icon(path: String, color: Color, size: Int, modifier: Modifier = Modifier, stroke: Float = 1.8f) {
    val p = remember(path) { iconPath(path) }
    Canvas(modifier.size(size.dp)) {
        val k = this.size.width / 24f
        scale(k, k, pivot = Offset.Zero) {
            drawPath(p, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A 24×24 filled glyph scaled to [size]. */
@Composable
fun Glyph(path: String, color: Color, size: Int, modifier: Modifier = Modifier) {
    val p = remember(path) { iconPath(path) }
    Canvas(modifier.size(size.dp)) {
        val k = this.size.width / 24f
        scale(k, k, pivot = Offset.Zero) { drawPath(p, color) }
    }
}
