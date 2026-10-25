package com.prayerhome.quran.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prayerhome.quran.data.Quran
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Geometry of the 1920×1080 canvas for the current menu state (all values in design px). */
class Frame(th: QTheme, menuOpen: Boolean, player: Boolean) {
    val sideW = if (menuOpen) th.sideW else th.railW
    val padL = if (menuOpen) 70 else 110
    val padR = if (menuOpen) 120 else 150
    val contentX = sideW + padL
    val contentW = 1920 - sideW - padL - padR
    val toolbarY = th.topPad
    val readTop = th.topPad + 52 + th.gap
    val playerY = 1080 - 28 - 104
    val readBottom = if (player) playerY - th.gap else 1080 - 28
    val colW = if (menuOpen) th.colW else 1380
    val colX = contentX + (contentW - colW) / 2
    val barRight = if (menuOpen) 46 else 60
    val barTop = 128
    val barBottom = if (player) 172 else 64
}

@Composable
fun ReaderScreen(m: ReaderModel) {
    val th = m.theme
    val rs by m.rec.state.collectAsState()
    val f = Frame(th, m.menuOpen, rs.active)
    val popupOpen = m.popup != Popup.NONE
    m.pxPerDp = LocalDensity.current.density

    Box(Modifier.size(1920.dp, 1080.dp).background(th.bg)) {
        // side: menu or rail
        CompositionLocalProvider(LocalNoFocus provides popupOpen) {
            if (m.menuOpen) SidePanel(m, f) else Rail(m, f)
        }
        CompositionLocalProvider(LocalNoFocus provides (popupOpen || m.menuOpen)) {
            Toolbar(m, f)
            if (rs.active) PlayerBar(m, f)
        }
        ReadingColumn(m, f, focusable = !popupOpen && !m.menuOpen)
        ProgressRail(m, f)

        when (m.popup) {
            Popup.OPTIONS -> { MarkScrim(m, f); VerseOptions(m, f) }
            Popup.FONT -> FontPopup(m, f)
            Popup.RECITER -> ReciterPopup(m, f)
            Popup.THEME -> ThemePopup(m, f)
            Popup.EXIT -> ExitPrompt(m, f)
            Popup.NONE -> {}
        }
        m.toast?.let { Toast(th, it, f, rs.active) }
    }
}

// ---------------------------------------------------------------- reading column

@Composable
private fun ReadingColumn(m: ReaderModel, f: Frame, focusable: Boolean) {
    val th = m.theme
    val h = f.readBottom - f.readTop
    val measurer = rememberTextMeasurer(cacheSize = 64)

    LaunchedEffect(m) {
        snapshotFlow { m.list.firstVisibleItemIndex to m.list.firstVisibleItemScrollOffset }.collectLatest {
            m.updateTop()
            delay(700)
            m.savePosition()
        }
    }

    Box(
        Modifier
            .offset(f.colX.dp, f.readTop.dp)
            .size(f.colW.dp, h.dp)
            .focusRequester(m.textFocus)
            .focusProperties { canFocus = focusable }
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                if (!down && e.type != KeyEventType.KeyUp) false else m.onReaderKey(e.key.nativeKeyCode, down)
            }
            .focusable(),
    ) {
        LazyColumn(
            state = m.list,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 60.dp, bottom = (h * 0.55f).dp),
        ) {
            items(m.items.size, key = { it }, contentType = { if (m.items[it] is Item.Header) 0 else 1 }) { i ->
                when (val item = m.items[i]) {
                    is Item.Header -> SurahHeader(m, item.surah)
                    is Item.Block -> VerseBlock(m, i, item, measurer)
                }
            }
        }
        // the top lines fade out under the toolbar, as in the designs
        Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(th.bg, th.bg.copy(alpha = 0.55f), th.bg.copy(alpha = 0f)))))
    }
}

fun quranStyle(m: ReaderModel, th: QTheme): TextStyle {
    val font = m.font
    return TextStyle(
        fontFamily = font.family,
        fontSize = m.size.sp,
        lineHeight = (m.size * font.lineHeight).sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        color = th.verse,
        textAlign = TextAlign.Right,
        textDirection = TextDirection.Rtl,
    )
}

@Composable
private fun VerseBlock(m: ReaderModel, index: Int, b: Item.Block, measurer: TextMeasurer) {
    val th = m.theme
    val bt = remember(index) { m.blockText(index) }
    val style = remember(th, m.fontIndex, m.size) { quranStyle(m, th) }
    val numStyle = remember(th, m.fontIndex) { TextStyle(fontFamily = m.font.family, fontSize = 20.sp, fontWeight = if (th.deco == Deco.GOLD) FontWeight.Bold else FontWeight.Normal) }
    val ms = th.markerSize
    // Placeholders only reserve the space; the markers are drawn in one pass below (cheaper than a
    // composable per marker). Lines are right-aligned, not justified: Compose's justified lines don't
    // report justified glyph positions, which would misplace markers and highlights.
    val inline = remember(index, th) {
        val ph = InlineTextContent(Placeholder((ms + 16).sp, ms.sp, PlaceholderVerticalAlign.TextCenter)) {}
        (b.from..b.to).associate { v -> v.toString() to ph }
    }
    val first = Quran.surahStart(b.surah)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    DisposableEffect(index) { onDispose { m.layouts.remove(index); m.coords.remove(index) } }
    BasicText(
        bt.text,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { m.coords[index] = it }
            .graphicsLayer { alpha = if (b.surah < m.currentSurah) 0.33f else 1f }
            .drawBehind {
                val l = layout ?: return@drawBehind
                val px = m.pxPerDp
                val inset = m.size * m.font.lineHeight * px * 0.07f
                val rad = (if (th.deco == Deco.GOLD) 6f else 12f) * px
                val rec = m.recIdx
                if (rec in b.from..b.to && rec != m.marked) {
                    for (r in m.verseRects(index, rec, l)) {
                        val rr = Rect(r.left - 8 * px, r.top + inset, r.right + 8 * px, r.bottom - inset)
                        drawRoundRect(th.recited, rr.topLeft, rr.size, CornerRadius(rad))
                        if (th.deco == Deco.GOLD) drawRect(th.gold, Offset(rr.left, rr.bottom - 3 * px), Size(rr.width, 3 * px))
                    }
                }
                val mk = m.marked
                if (mk in b.from..b.to) {
                    for (r in m.verseRects(index, mk, l)) {
                        val rr = Rect(r.left - 8 * px, r.top + inset, r.right + 8 * px, r.bottom - inset)
                        drawRoundRect(th.pick, rr.topLeft, rr.size, CornerRadius(rad))
                        drawRoundRect(th.accent, rr.topLeft, rr.size, CornerRadius(rad), style = Stroke(3 * px))
                    }
                }
            }
            .drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                val d = ms * m.pxPerDp
                val rec = m.recIdx
                val rects = l.placeholderRects
                for (k in bt.starts.indices) {
                    val v = b.from + k
                    val pr = rects.getOrNull(k) ?: break
                    val cx = pr.center.x
                    val cy = pr.center.y
                    val on = rec == v
                    translate(cx - d / 2, cy - d / 2) {
                        verseMarker(th.deco, th.gold, if (on) (if (th.deco == Deco.GOLD) th.accent else th.gold) else null, d)
                    }
                    val color = if (on) (if (th.deco == Deco.GOLD) th.onAccent else th.bg) else (if (th.deco == Deco.GOLD) th.arAccent else th.gold)
                    val r = measurer.measure(Quran.arabicNumber(v - first + 1), numStyle.copy(color = color))
                    drawText(r, topLeft = Offset(cx - r.size.width / 2f, cy - r.size.height / 2f))
                }
            },
        style = style,
        inlineContent = inline,
        onTextLayout = { layout = it; m.layouts[index] = it },
    )
}

@Composable
private fun SurahHeader(m: ReaderModel, s: Int) {
    val th = m.theme
    val meta = if (Quran.meccan(s)) "Meccan" else "Medinan"
    val n = Quran.ayahs(s)
    val dim = Modifier.graphicsLayer { alpha = if (s < m.currentSurah) 0.33f else 1f }
    Column(dim.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when (th.deco) {
            Deco.AMBER -> Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Box(Modifier.weight(1f).height(1.dp).background(th.border))
                Canvas(Modifier.size(22.dp)) { drawContextRotatedSquare(th.gold) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Ar("سورة " + Quran.nameAr(s), Fonts.ReemKufi, 50, th.text, weight = 500, lineHeight = 1.4f)
                    T("${meta.uppercase()} · $n VERSES", th.ui, 18, th.muted, spacing = 0.2f)
                }
                Canvas(Modifier.size(22.dp)) { drawContextRotatedSquare(th.gold) }
                Box(Modifier.weight(1f).height(1.dp).background(th.border))
            }
            Deco.INDIGO -> Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f).height(1.dp).background(th.border))
                Box(Modifier.size(500.dp, 150.dp)) {
                    Canvas(Modifier.fillMaxSize()) { arch(c(0x141B30), th.accent, th.border, th.gold) }
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Ar("سورة " + Quran.nameAr(s), Fonts.ElMessiri, 44, c(0xF2F5FA), weight = 600, lineHeight = 1.5f)
                        T("${meta.uppercase()} · $n VERSES", th.ui, 16, th.muted, spacing = 0.22f)
                    }
                }
                Box(Modifier.weight(1f).height(1.dp).background(th.border))
            }
            Deco.GOLD -> {
                Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.weight(1f).height(1.dp).background(th.gold))
                    Box(Modifier.size(116.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) { medallion(th.bg, th.gold, th.fill, th.card) }
                        Ar(Quran.arabicNumber(s), m.font.family, 32, th.arAccent, weight = 700)
                    }
                    Box(Modifier.weight(1f).height(1.dp).background(th.gold))
                }
                Ar("سورة " + Quran.nameAr(s), Fonts.ArefRuqaa, 54, th.arAccent, weight = 700, lineHeight = 1.55f)
                T("$meta · $n verses", Fonts.Cormorant, 22, th.muted, weight = 500, italic = true)
            }
        }
        if (Quran.hasBasmalaHeader(s)) {
            val bSize = (m.size * (if (th.deco == Deco.GOLD) 0.9f else 0.92f)).toInt()
            val color = if (th.deco == Deco.GOLD) th.arAccent else th.gold
            Box(
                Modifier.drawBehind {
                    if (m.recBasmala == s) {
                        val px = m.pxPerDp
                        drawRoundRect(th.recited, Offset(-14 * px, size.height * 0.12f), Size(size.width + 28 * px, size.height * 0.76f), CornerRadius(12 * px))
                    }
                },
            ) {
                Ar(m.quran.basmala, m.font.family, bSize, color, lineHeight = if (th.deco == Deco.INDIGO) 1.9f else 2f, align = TextAlign.Center)
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawContextRotatedSquare(color: Color) {
    val k = size.width / 22f
    rotate(45f, Offset(size.width / 2, size.height / 2)) {
        drawRect(color, Offset(6 * k, 6 * k), Size(10 * k, 10 * k))
    }
}

private fun c(v: Long) = Color(0xFF000000 or v)

// ---------------------------------------------------------------- progress rail

@Composable
private fun ProgressRail(m: ReaderModel, f: Frame) {
    val th = m.theme
    val s = m.currentSurah
    val total = Quran.ayahs(s)
    val ayah = (m.progressVerse - Quran.surahStart(s) + 1).coerceIn(1, total)
    val frac = ayah.toFloat() / total
    val h = 1080 - f.barTop - f.barBottom
    val x = 1920 - f.barRight - 6
    Box(Modifier.offset(x.dp, f.barTop.dp).size(6.dp, h.dp).background(th.track, RoundedCornerShape(6.dp))) {
        Box(Modifier.size(6.dp, (h * frac).dp).background(th.fill, RoundedCornerShape(6.dp)))
    }
    // knob with its 4px ring in the page colour
    Box(Modifier.offset((x - 10).dp, (f.barTop + h * frac - 13).dp).size(26.dp).background(th.bg, CircleShape))
    Box(Modifier.offset((x - 6).dp, (f.barTop + h * frac - 9).dp).size(18.dp).background(if (th.deco == Deco.GOLD) th.accent else th.fill, CircleShape))
    // "ayah / total" pill, right-aligned 24px left of the rail
    Box(Modifier.offset(0.dp, (f.barTop + h * frac - 16).dp).width((x - 24).dp), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (th.deco == Deco.GOLD) th.playerBg else th.card)
                .border1(th.border, 999)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) { T("$ayah / $total", th.ui, 15, th.text) }
    }
    Box(Modifier.offset((x - 30).dp, (f.barTop - 32).dp).width(66.dp), contentAlignment = Alignment.TopCenter) { T("1", th.ui, 14, th.muted) }
    Box(Modifier.offset((x - 30).dp, (f.barTop + h + 14).dp).width(66.dp), contentAlignment = Alignment.TopCenter) { T("$total", th.ui, 14, th.muted) }
}

fun Modifier.border1(color: Color, radius: Int, width: Float = 1f): Modifier =
    this.border(width.dp, color, RoundedCornerShape(radius.dp))

// ---------------------------------------------------------------- scrim behind the verse options

@Composable
private fun MarkScrim(m: ReaderModel, f: Frame) {
    val th = m.theme
    var me by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Canvas(Modifier.size(1920.dp, 1080.dp).onGloballyPositioned { me = it }) {
        val v = m.marked
        val bi = if (v >= 0) m.blockOf[v] else -1
        val l = m.layouts[bi]
        val bc = m.coords[bi]
        val mine = me
        val hole = Path()
        if (l != null && bc != null && mine != null && bc.isAttached) {
            val px = m.pxPerDp
            val inset = m.size * m.font.lineHeight * px * 0.07f
            for (r in m.verseRects(bi, v, l)) {
                val tl = mine.localPositionOf(bc, Offset(r.left - 12 * px, r.top + inset - 4 * px))
                val br = mine.localPositionOf(bc, Offset(r.right + 12 * px, r.bottom - inset + 4 * px))
                hole.addRoundRect(RoundRect(Rect(tl, br), CornerRadius(14 * px)))
            }
        }
        // only the side, the toolbar and the text column are dimmed; the marked verse stays bright
        val sidePx = f.sideW * m.pxPerDp
        clipPath(hole, ClipOp.Difference) {
            drawRect(th.scrim, Offset(sidePx, 0f), Size(size.width - sidePx, size.height))
        }
    }
}
