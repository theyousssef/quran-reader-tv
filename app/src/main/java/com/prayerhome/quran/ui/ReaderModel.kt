package com.prayerhome.quran.ui

import android.util.Log
import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.prayerhome.quran.App
import com.prayerhome.quran.audio.LeaveGuard
import com.prayerhome.quran.data.Quran
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.exp
import kotlin.math.roundToInt

/** Logs the follow loop's clock and scroll position every frame (tag QRFollow). */
private const val DEBUG_FOLLOW = false

/** The reading column is a list of surah headers and page-sized blocks of flowing verses. */
sealed class Item(val surah: Int) {
    class Header(surah: Int) : Item(surah)
    /** Verses [from]..[to] (global indices, inclusive) of one surah on one mushaf page. */
    class Block(surah: Int, val from: Int, val to: Int) : Item(surah)
}

/**
 * A block's text: the verses, each followed by a no-break space and a placeholder for its end marker.
 * [phStarts]/[phEnds] are the placeholder ranges. [page] is the mushaf page that ends with this block's
 * last verse (its marker then carries a page badge too), or 0.
 */
class BlockText(val text: AnnotatedString, val starts: IntArray, val ends: IntArray, val phStarts: IntArray, val phEnds: IntArray, val page: Int)

/**
 * A block laid out at the current theme, font, size and width. When the block's first line carries on the
 * previous block's last line (the page break falls mid-line), [shift] is that line's height: the item is
 * [shift] px shorter and its text is drawn [shift] px above it, onto the previous block's last line.
 */
class BlockLayout(val layout: TextLayoutResult, val shift: Float, val height: Int)

/** A number laid out for a marker, with the centre of its glyphs' ink (not of its line box). */
class InkText(val layout: TextLayoutResult, val cx: Float, val cy: Float)

enum class Zone { TEXT, TOOLBAR, PLAYER, MENU }
enum class Popup { NONE, FONT, RECITER, THEME, OPTIONS, EXIT }

class ReaderModel(val app: App, val quran: Quran, private val scope: CoroutineScope, private val exit: () -> Unit) {
    private val prefs = app.prefs
    val rec = app.recitation

    // ------------------------------------------------------------ structure

    val items: List<Item>
    /** Item index of the block holding each verse. */
    val blockOf = IntArray(Quran.VERSES)
    /** Item index of each surah's header. */
    val headerOf = IntArray(115)

    init {
        val list = ArrayList<Item>(800)
        for (s in 1..114) {
            headerOf[s] = list.size
            list += Item.Header(s)
            val first = Quran.surahStart(s)
            val last = first + Quran.ayahs(s) - 1
            var from = first
            for (v in first..last) {
                val pageBreak = v > from && Quran.pageOf(v) != Quran.pageOf(v - 1)
                if (pageBreak) { list += Item.Block(s, from, v - 1); from = v }
                blockOf[v] = list.size
            }
            list += Item.Block(s, from, last)
        }
        items = list
    }

    private val texts = HashMap<Int, BlockText>()

    fun blockText(index: Int): BlockText = texts.getOrPut(index) {
        val b = items[index] as Item.Block
        val n = b.to - b.from + 1
        val starts = IntArray(n); val ends = IntArray(n); val ps = IntArray(n); val pe = IntArray(n)
        val sb = StringBuilder()
        for (k in 0 until n) {
            val v = b.from + k
            starts[k] = sb.length
            sb.append(quran.text(v))
            ends[k] = sb.length
            sb.append(' ') // keep the marker on the verse's last line
            ps[k] = sb.length
            sb.append('۝').append(Quran.arabicNumber(v - Quran.surahStart(b.surah) + 1))
            pe[k] = sb.length
            if (k < n - 1) sb.append(' ')
        }
        val pageEnds = b.to == Quran.VERSES - 1 || Quran.pageOf(b.to + 1) != Quran.pageOf(b.to)
        BlockText(AnnotatedString(sb.toString()), starts, ends, ps, pe, if (pageEnds) Quran.pageOf(b.to) else 0)
    }

    // ------------------------------------------------------------ settings

    var themeIndex by mutableIntStateOf(prefs.theme.coerceIn(0, THEMES.size - 1))
        private set
    var fontIndex by mutableIntStateOf(prefs.font.coerceIn(0, QURAN_FONTS.size - 1))
        private set
    var sizeDelta by mutableIntStateOf(prefs.sizeDelta)
        private set
    val theme get() = THEMES[themeIndex]
    val font get() = QURAN_FONTS[fontIndex]
    val size get() = (font.size + sizeDelta).coerceIn(36, 84)

    fun setTheme(i: Int) {
        themeIndex = i; prefs.theme = i
        setFont(THEMES[i].pairedFont)
    }
    fun setFont(i: Int) { fontIndex = i; prefs.font = i }
    fun textSmaller() = changeSize(-4)
    fun textLarger() = changeSize(+4)
    private fun changeSize(d: Int) {
        val target = (size + d).coerceIn(36, 84)
        sizeDelta = target - font.size; prefs.sizeDelta = sizeDelta
    }

    // ------------------------------------------------------------ ui state

    var menuOpen by mutableStateOf(false)
        private set
    var tab by mutableIntStateOf(0)
    var zone by mutableStateOf(Zone.TEXT)
        private set
    var popup by mutableStateOf(Popup.NONE)
        private set
    private var popupFrom = Zone.TEXT
    /** Marked verse (global index) or -1. */
    var marked by mutableIntStateOf(-1)
        private set
    /** The bookmarked verse; there is always one (Al-Fatiha 1 by default). */
    var bookmark by mutableIntStateOf(prefs.bookmark)
        private set
    var toast by mutableStateOf<String?>(null)
        private set
    private var toastJob: Job? = null
    /** Verse lit up by "Continue from bookmark" ([bookmarkGlow] fades it out); not the mark. */
    var glowVerse by mutableIntStateOf(-1)
        private set
    val bookmarkGlow = Animatable(0f)
    private var glowJob: Job? = null

    /** First verse visible at the top of the reading column (drives the header and resume). */
    var topVerse by mutableIntStateOf(prefs.topVerse.coerceIn(0, Quran.VERSES - 1))
        private set
    /** The surah being read: the one whose header sits in the upper half, else the top verse's. */
    var currentSurah by mutableIntStateOf(Quran.refOf(topVerse).surah)
        private set
    /** First visible verse of [currentSurah] (drives the progress bar). */
    var progressVerse by mutableIntStateOf(topVerse)
        private set

    /** Recited verse (global index), or -1; [recBasmala] is the surah whose basmala is being recited. */
    var recIdx by mutableIntStateOf(-1)
        private set
    var recBasmala by mutableIntStateOf(0)
        private set
    /** Audio Recitation mode: a recitation exists (playing or paused), so the player bar is up. */
    var recMode by mutableStateOf(false)
        private set
    /** How far the current clip has played, 0..1, updated every frame (read it only in draw/layout lambdas). */
    val clipProgress = mutableFloatStateOf(0f)
    /** The recited surah's progress for the rail, 0..1, set in the same frame as [clipProgress] (draw/layout only). */
    val surahProgress = mutableFloatStateOf(0f)
    var clipSecond by mutableIntStateOf(0)
        private set
    var clipSeconds by mutableIntStateOf(0)
        private set

    val list = LazyListState(prefs.scrollItem.coerceIn(0, items.size - 1).takeIf { prefs.scrollItem >= 0 } ?: 0, prefs.scrollOffset.coerceAtLeast(0))
    val coords = HashMap<Int, LayoutCoordinates>()
    var pxPerDp = 1f
    /** The list is this much taller than the visible column (hidden below it), so a block whose first line
     *  shares the previous block's last line is composed before that line scrolls into view. */
    var extraPx = 0f

    val textFocus = FocusRequester()
    val toolFocus = FocusRequester()
    val playFocus = FocusRequester()
    val bannerFocus = FocusRequester()
    /** Play/pause has focus (Back there is the app's last step). */
    var playFocused = false

    // ------------------------------------------------------------ text layout

    var measurer: TextMeasurer? = null
    /** Width of the reading column in px. */
    var columnPx by mutableIntStateOf(0)
    private var layoutKey: List<Int> = emptyList()
    /** Width taken by each block's last line, from the right edge (NaN = not measured at this key). */
    private val endUsed = FloatArray(items.size) { Float.NaN }
    private val layoutCache = object : LinkedHashMap<Int, BlockLayout>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BlockLayout>?) = size > 40
    }
    private val inkCache = HashMap<String, InkText>()

    private fun checkKey() {
        val k = listOf(themeIndex, fontIndex, size, columnPx)
        if (k != layoutKey) {
            layoutKey = k
            endUsed.fill(Float.NaN)
            layoutCache.clear()
            inkCache.clear()
        }
    }

    /** The block's layout; a block continues the previous one's last line, so a surah's earlier blocks are measured first. */
    fun blockLayout(i: Int): BlockLayout {
        checkKey()
        layoutCache[i]?.let { return it }
        var j = i
        while (items[j - 1] is Item.Block && endUsed[j - 1].isNaN()) j--
        for (k in j until i) measureBlock(k)
        return measureBlock(i)
    }

    private fun measureBlock(i: Int): BlockLayout {
        val tm = measurer!!
        val th = theme
        val b = items[i] as Item.Block
        val bt = blockText(i)
        val base = quranStyle(this, th)
        val w = columnPx
        var indent = 0f
        if (items[i - 1] is Item.Block) {
            val used = endUsed[i - 1]
            val gap = size * pxPerDp * 0.3f
            val word = quran.text(b.from).substringBefore(' ')
            val ww = tm.measure(word, base, softWrap = false).size.width
            if (used + gap + ww + 8 * pxPerDp <= w) indent = used + gap
        }
        val style = if (indent > 0f) base.copy(textIndent = TextIndent(firstLine = (indent / pxPerDp).sp)) else base
        val ms = th.markerSize
        val ph = Placeholder((ms + 16).sp, ms.sp, PlaceholderVerticalAlign.TextCenter)
        val n = bt.starts.size
        val phs = List(n) { k ->
            val p = if (k == n - 1 && bt.page > 0) Placeholder((ms + 20 + badgeW(th)).sp, ms.sp, PlaceholderVerticalAlign.TextCenter) else ph
            AnnotatedString.Range(p, bt.phStarts[k], bt.phEnds[k])
        }
        val l = tm.measure(bt.text, style, placeholders = phs, constraints = Constraints.fixedWidth(w), skipCache = true)
        val last = l.lineCount - 1
        endUsed[i] = w - l.getLineLeft(last)
        val shift = if (indent > 0f) l.getLineBottom(0) else 0f
        val bl = BlockLayout(l, shift, (l.getLineBottom(last) - shift).roundToInt())
        layoutCache[i] = bl
        return bl
    }

    /** Verse numbers and page numbers, laid out once and centred on their ink. */
    fun inkText(text: String, page: Boolean): InkText {
        checkKey()
        return inkCache.getOrPut((if (page) "p" else "v") + text) {
            val tm = measurer!!
            val th = theme
            val box = if (page) badgeH(th) else th.markerSize.toFloat()
            // the largest ink box that fits inside each ornament's inner shape, as a share of its size
            val (maxW, maxH) = if (page) badgeInnerW(th) / box to 0.56f else when (th.deco) {
                Deco.AMBER -> 0.50f to 0.34f
                Deco.INDIGO -> 0.48f to 0.34f
                Deco.GOLD -> 0.52f to 0.36f
            }
            var fs = if (page) badgeH(th) * 0.7f else 28f
            var ink = measureInk(tm, text, fs, page)
            val scale = minOf(1f, maxW * box * pxPerDp / ink.second.width.coerceAtLeast(1f), maxH * box * pxPerDp / ink.second.height.coerceAtLeast(1f))
            if (scale < 0.98f) { fs *= scale; ink = measureInk(tm, text, fs, page) }
            val r = ink.second
            InkText(ink.first, (r.left + r.right) / 2f, (r.top + r.bottom) / 2f)
        }
    }

    var fontResolver: FontFamily.Resolver? = null
    private val inkPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val inkBounds = android.graphics.Rect()

    /** Lays out [text] and finds its glyphs' ink box (font metrics put Arabic-Indic digits off-centre in the line box). */
    private fun measureInk(tm: TextMeasurer, text: String, fs: Float, page: Boolean): Pair<TextLayoutResult, Rect> {
        val bold = theme.deco == Deco.GOLD || page
        val weight = if (bold) FontWeight.Bold else FontWeight.Normal
        val l = tm.measure(text, TextStyle(fontFamily = font.family, fontSize = fs.sp, fontWeight = weight), softWrap = false)
        val tf = runCatching { fontResolver?.resolve(font.family, weight)?.value as? android.graphics.Typeface }.getOrNull()
        if (tf == null) return l to Rect(0f, 0f, l.size.width.toFloat(), l.size.height.toFloat())
        inkPaint.typeface = tf
        inkPaint.textSize = fs * pxPerDp
        inkPaint.getTextBounds(text, 0, text.length, inkBounds)
        val adv = inkPaint.measureText(text)
        // the run is laid out from the left of the layout; align Paint's origin with it and with the baseline
        val x0 = (l.size.width - adv) / 2f
        val base = l.firstBaseline
        return l to Rect(x0 + inkBounds.left, base + inkBounds.top, x0 + inkBounds.right, base + inkBounds.bottom)
    }

    // ------------------------------------------------------------ reading position

    private val visH get() = list.layoutInfo.let { (it.viewportEndOffset - it.viewportStartOffset).toFloat() } - extraPx

    fun updateTop() {
        val info = list.layoutInfo
        val vis = info.visibleItemsInfo
        if (vis.isEmpty() || measurer == null) return
        val vpH = visH
        var header = -1
        for (it in vis) {
            val item = items[it.index]
            if (item is Item.Header && it.offset + it.size / 2 >= 0 && it.offset + it.size / 2 <= vpH / 2) { header = item.surah; break }
        }
        // The first verse whose first line is on screen below the faded top strip — unless the verse before it still fills
        // the top third (a long verse scrolled half-way), which then counts as the first visible one.
        var first = -1
        var firstY = 0f
        var covering = -1
        loop@ for (it in vis) {
            val item = items[it.index] as? Item.Block ?: continue
            val bl = blockLayout(it.index)
            val l = bl.layout
            val bt = blockText(it.index)
            for (k in bt.starts.indices) {
                val y = it.offset + l.getLineTop(l.getLineForOffset(bt.starts[k])) - bl.shift
                if (y >= 50f * pxPerDp) { first = item.from + k; firstY = y; break@loop }
                covering = item.from + k
            }
        }
        if (covering >= 0 && (first < 0 || firstY > vpH / 3f)) first = covering
        if (first < 0) {
            val h = items[vis.first().index]
            first = if (h is Item.Header) Quran.surahStart(h.surah) else (h as Item.Block).from
        }
        topVerse = first
        val surah = if (header > 0) header else Quran.refOf(first).surah
        currentSurah = surah
        progressVerse = if (Quran.refOf(first).surah == surah) first else Quran.surahStart(surah)
    }

    fun savePosition() {
        prefs.scrollItem = list.firstVisibleItemIndex
        prefs.scrollOffset = list.firstVisibleItemScrollOffset
        prefs.topVerse = topVerse
    }

    fun onLeave() {
        savePosition()
        prefs.continueVerse = topVerse
    }

    private val topSafe get() = 96f * pxPerDp

    /** Vertical extent of a verse in viewport pixels, if its block is laid out and visible. */
    private fun verseSpan(v: Int): Pair<Float, Float>? {
        val bi = blockOf[v]
        val it = list.layoutInfo.visibleItemsInfo.firstOrNull { x -> x.index == bi } ?: return null
        if (measurer == null) return null
        val bl = blockLayout(bi)
        val l = bl.layout
        val bt = blockText(bi)
        val k = v - (items[bi] as Item.Block).from
        val top = l.getLineTop(l.getLineForOffset(bt.starts[k])) - bl.shift
        val bottom = l.getLineBottom(l.getLineForOffset((bt.ends[k] - 1).coerceAtLeast(bt.starts[k]))) - bl.shift
        return (it.offset + top) to (it.offset + bottom)
    }

    private suspend fun frames(n: Int) = repeat(n) { withFrameNanos { } }

    /** Open a verse at the top of the column (surah starts show their header). */
    suspend fun goTo(v: Int) {
        val r = Quran.refOf(v)
        if (r.ayah == 1) { list.scrollToItem(headerOf[r.surah]); return }
        val bi = blockOf[v]
        list.scrollToItem(bi)
        frames(2)
        val span = verseSpan(v) ?: return
        list.scrollBy(span.first - topSafe)
    }

    /** Make sure a verse is comfortably on screen; scroll only when it isn't. */
    suspend fun reveal(v: Int) {
        val vpH = visH
        var span = verseSpan(v)
        if (span == null) {
            val r = Quran.refOf(v)
            if (r.ayah == 1) list.scrollToItem(headerOf[r.surah]) else list.scrollToItem(blockOf[v])
            frames(2)
            span = verseSpan(v) ?: return
            if (span.first >= topSafe && span.second <= vpH - 8) return
            list.scrollBy(span.first - topSafe)
            return
        }
        val (top, bottom) = span
        if (top >= topSafe && bottom <= vpH - 8) return
        val delta = if (bottom - top > vpH - topSafe - vpH * 0.2f) top - topSafe else top - vpH * 0.28f
        if (abs(delta) < vpH * 1.5f) list.animateScrollBy(delta, tween(450)) else list.scrollBy(delta)
    }

    private fun scrollStep(dir: Int) {
        val step = size * font.lineHeight * 2f * pxPerDp
        if (recMode) userScrolled = true
        scope.launch { list.animateScrollBy(dir * step, tween(220)) }
    }

    // ------------------------------------------------------------ recitation link

    fun onRecitation(active: Boolean, surah: Int, ayah: Int, basmala: Boolean) {
        val was = recMode
        recMode = active
        if (!active) {
            recIdx = -1; recBasmala = 0
            if (was && popup == Popup.NONE) { menuOpen = false; focusText() }
            return
        }
        if (basmala) { recIdx = -1; recBasmala = surah } else { recIdx = Quran.indexOf(surah, ayah); recBasmala = 0 }
        if (!was && popup == Popup.NONE) { menuOpen = false; focusPlayer() }
    }

    /** The user scrolled away during recitation: stop following until the next verse starts. */
    private var userScrolled = false

    /**
     * Follows the recitation: estimates the clip's position and scrolls so that the point of the text being
     * recited — moving through the verse's lines in step with the audio — sits on the reading line.
     *
     * - The clock never runs backwards within a clip: it advances by frame time at a rate nudged toward the
     *   MediaPlayer position (read 5× a second), so read jitter is absorbed gradually instead of by jumps.
     *   Only a repeat (the clip starting over) or a new verse resets it.
     * - The list is scrolled by whole pixels only (see [track]).
     * - Paused, or waiting for the next verse's audio, it requests no frames once the page is at rest.
     */
    suspend fun follow() {
        var clip = Int.MIN_VALUE
        var clock = 0f
        var dur = 0
        var readAt = 0L
        var readPos = 0f
        var lastRaw = -1f
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val s = rec.state.value
            if (!s.active) break
            val key = if (s.basmala) -s.surah else Quran.indexOf(s.surah, s.ayah)
            if (key != clip) { clip = key; clock = 0f; dur = 0; readAt = 0L; lastRaw = -1f; userScrolled = false; scrollAcc = 0f }
            val running = s.playing && !s.buffering
            val dt = if (last == 0L) 0f else ((now - last) / 1e6f).coerceIn(0f, 100f)
            last = now
            if (running) {
                if (readAt == 0L || now - readAt > 200_000_000L) {
                    val raw = rec.positionMs().toFloat()
                    dur = rec.durationMs()
                    if (lastRaw >= 0f && raw < lastRaw - 500f) clock = raw   // repeat: the clip started over
                    lastRaw = raw; readPos = raw; readAt = now
                }
                val err = readPos + (now - readAt) / 1e6f - clock
                clock += if (err > 1500f) err else dt * (1f + err / 300f).coerceIn(0f, 2f)
            } else if (dur == 0) dur = rec.durationMs()
            clock = clock.coerceIn(0f, dur.toFloat().coerceAtLeast(0f))
            val p = if (dur > 0) clock / dur else 0f
            clipProgress.floatValue = p
            surahProgress.floatValue = if (s.basmala) 0f else ((s.ayah - 1 + p) / Quran.ayahs(s.surah)).coerceIn(0f, 1f)
            clipSecond = (clock / 1000f).toInt()
            clipSeconds = dur / 1000
            val still = userScrolled || measurer == null || track(s.surah, s.basmala, key, p, dt)
            if (DEBUG_FOLLOW) Log.d("QRFollow", "t=${now / 1_000_000} run=$running clock=$clock p=$p item=${list.firstVisibleItemIndex} off=${list.firstVisibleItemScrollOffset}")
            if (!running && still) {
                // At rest and nothing playing: no frames until the recitation changes; a slow check re-settles
                // the page if its layout moved (e.g. the text size changed).
                last = 0L
                withTimeoutOrNull(400) { rec.state.first { it != s } }
            }
        }
        clipProgress.floatValue = 0f
        surahProgress.floatValue = 0f
    }

    /** Sub-pixel part of the scroll still owed (always less than a pixel). */
    private var scrollAcc = 0f

    /**
     * Glides the recited point toward the reading line, in whole pixels only: the point is brought to rest
     * 0..1 px below the line (the target is floored), never past it. Fractional deltas used to be rounded
     * by the list, so a target between two pixels made the page flip between them every frame (the shiver
     * on slow, short verses and while paused). Returns true when there's nothing to move.
     */
    private fun track(surah: Int, basmala: Boolean, v: Int, p: Float, dt: Float): Boolean {
        val vis = list.layoutInfo.visibleItemsInfo
        val y: Float = if (basmala) {
            val hi = headerOf[surah]
            val it = vis.firstOrNull { it.index == hi } ?: run { list.requestScrollToItem(hi); return false }
            val basH = size * 0.92f * 2f * pxPerDp
            it.offset + it.size - basH * (1f - p)
        } else {
            val bi = blockOf[v]
            val it = vis.firstOrNull { it.index == bi } ?: run { list.requestScrollToItem(bi); return false }
            it.offset + pointInVerse(bi, v, p)
        }
        val vh = visH
        val e = y - vh * 0.36f
        val goal = floor(e)
        if (goal == 0f) { scrollAcc = 0f; return true }
        if (abs(e) > vh * 2.5f) { scrollAcc = 0f; return list.dispatchRawDelta(goal) == 0f }
        if (scrollAcc * goal < 0f) scrollAcc = 0f
        scrollAcc += e * (1f - exp(-dt / 110f))
        val n = if (goal > 0f) floor(scrollAcc).coerceIn(0f, goal) else ceil(scrollAcc).coerceIn(goal, 0f)
        scrollAcc = (scrollAcc - n).coerceIn(-1f, 1f)
        // At the end of the list nothing can move: count that as at rest.
        return n != 0f && list.dispatchRawDelta(n) == 0f
    }

    /**
     * Block-local y of the point [p] (0..1) of the way through verse [v]: the share of the verse's characters
     * is mapped onto its lines, and within a line onto its width from the right edge, so the point moves down
     * one line height per full line and carries on across verse and page boundaries without jumping.
     */
    private fun pointInVerse(bi: Int, v: Int, p: Float): Float {
        val bl = blockLayout(bi)
        val l = bl.layout
        val bt = blockText(bi)
        val k = v - (items[bi] as Item.Block).from
        val s = bt.starts[k]; val e = bt.ends[k]
        val c = s + p.coerceIn(0f, 1f) * (e - s)
        val line = l.getLineForOffset(c.toInt().coerceIn(s, (e - 1).coerceAtLeast(s)))
        val ls = l.getLineStart(line); val le = l.getLineEnd(line).coerceAtLeast(ls + 1)
        val right = l.getLineRight(line); val left = l.getLineLeft(line)
        val x = right - (c - ls) / (le - ls) * (right - left)
        val w = columnPx.toFloat()
        val frac = ((w - x) / w).coerceIn(0f, 1f)
        val top = l.getLineTop(line); val bottom = l.getLineBottom(line)
        return top + frac * (bottom - top) - bl.shift
    }

    // ------------------------------------------------------------ zones and popups

    fun focusText() { zone = Zone.TEXT; requestLater(textFocus) }
    fun focusToolbar() { zone = Zone.TOOLBAR; requestLater(toolFocus) }
    fun focusPlayer() { zone = Zone.PLAYER; requestLater(playFocus, 2) }
    fun focusBanner() { zone = Zone.TOOLBAR; requestLater(bannerFocus) }
    /** Leave the text/menu for "the main place": play/pause in recitation mode, the text otherwise. */
    fun focusHome() = if (recMode) focusPlayer() else focusText()

    fun openMenu(t: Int = 0) { tab = t; menuOpen = true; zone = Zone.MENU }
    fun closeMenu() { menuOpen = false; focusHome() }

    fun openPopup(p: Popup) { popupFrom = zone; popup = p }
    fun closePopup() {
        val from = popupFrom
        popup = Popup.NONE
        when (from) {
            Zone.TOOLBAR -> focusToolbar()
            Zone.PLAYER -> focusPlayer()
            else -> focusHome()
        }
    }

    private fun requestLater(r: FocusRequester, n: Int = 1) {
        scope.launch { frames(n); runCatching { r.requestFocus() } }
    }

    fun showToast(msg: String) {
        toast = msg
        toastJob?.cancel()
        toastJob = scope.launch { delay(2200); toast = null }
    }

    // ------------------------------------------------------------ actions

    /** Open a verse from the menu; in recitation mode the recitation moves there too. */
    fun open(v: Int) {
        menuOpen = false
        marked = -1
        if (recMode) {
            val r = Quran.refOf(v)
            rec.seek(r.surah, r.ayah)
            focusPlayer()
        } else focusText()
        scope.launch { goTo(v); frames(1); updateTop() }
    }

    /** "Continue from bookmark": go to the bookmarked verse and light it up for a moment. */
    fun openBookmark() {
        val v = bookmark
        open(v)
        glowVerse = v
        glowJob?.cancel()
        glowJob = scope.launch {
            bookmarkGlow.snapTo(1f)
            delay(2600)
            bookmarkGlow.animateTo(0f, tween(1400))
            glowVerse = -1
        }
    }

    fun exitRecitation() {
        rec.stop()
        menuOpen = false
        focusText()
    }

    fun reciteMarked() {
        val v = marked.takeIf { it >= 0 } ?: topVerse
        val r = Quran.refOf(v)
        marked = -1
        popup = Popup.NONE
        focusText()
        rec.start(r.surah, r.ayah)
    }

    fun bookmarkMarked() {
        val v = marked.takeIf { it >= 0 } ?: return
        bookmark = v; prefs.bookmark = v
        val r = Quran.refOf(v)
        marked = -1
        popup = Popup.NONE
        focusText()
        showToast("Bookmark moved to ${Quran.nameEn(r.surah)} · verse ${r.ayah}")
    }

    /** Back, from the innermost layer out; the last step leaves the app. */
    fun back() {
        when {
            popup != Popup.NONE -> closePopup()
            menuOpen -> closeMenu()
            recMode && !playFocused -> focusPlayer()
            !recMode && (zone == Zone.TOOLBAR || zone == Zone.PLAYER) -> focusText()
            marked >= 0 -> marked = -1
            else -> {
                val s = rec.state.value
                if (s.active && s.playing) openPopup(Popup.EXIT)
                else exit()
            }
        }
    }

    /** The "keep playing?" prompt (Back or Home while reciting): both answers go to the home screen. */
    fun exitKeepPlaying() { popup = Popup.NONE; LeaveGuard.leave(app, keepPlaying = true) }
    fun exitPause() { popup = Popup.NONE; LeaveGuard.leave(app, keepPlaying = false) }

    /** Keys while the reading column has focus. */
    fun onReaderKey(code: Int, down: Boolean): Boolean {
        if (recMode) {
            // The text isn't a stop in recitation mode; send the remote back to the player.
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) focusPlayer()
                return true
            }
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) scrollStep(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) scrollStep(+1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!down) return true
                if (marked >= 0) moveMark(+1) else openMenu(0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!down) return true
                if (marked >= 0) moveMark(-1) else focusToolbar()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Act on key-up so the release doesn't land on the box that opens.
                if (down) return true
                if (marked >= 0) openPopup(Popup.OPTIONS)
                else { updateTop(); marked = topVerse; Log.d("QuranReader", "marked ${Quran.refOf(marked)}") }
                return true
            }
        }
        return false
    }

    /** Left = next verse (reading right-to-left), Right = previous. */
    private fun moveMark(d: Int) {
        val v = (marked + d).coerceIn(0, Quran.VERSES - 1)
        if (v == marked) return
        marked = v
        scope.launch { reveal(v) }
    }

    /** Media keys while the app is in front. */
    fun onMediaKey(code: Int): Boolean {
        val s = rec.state.value
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK ->
                if (s.active) rec.togglePause() else reciteMarked()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (s.active) rec.play() else reciteMarked()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> rec.pause()
            KeyEvent.KEYCODE_MEDIA_STOP -> rec.stop()
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> rec.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> rec.previous()
            else -> return false
        }
        return true
    }

    /** Rectangles (block-local, before the block's shift) covering a verse's text, one per line. */
    fun verseRects(blockIndex: Int, v: Int, l: TextLayoutResult): List<Rect> {
        val b = items[blockIndex] as Item.Block
        val bt = blockText(blockIndex)
        val k = v - b.from
        if (k < 0 || k >= bt.starts.size) return emptyList()
        val s = bt.starts[k]; val e = bt.ends[k]
        val out = ArrayList<Rect>(4)
        val l0 = l.getLineForOffset(s); val l1 = l.getLineForOffset((e - 1).coerceAtLeast(s))
        for (line in l0..l1) {
            val a = maxOf(s, l.getLineStart(line))
            val z = minOf(e, l.getLineEnd(line, visibleEnd = true))
            if (z <= a) continue
            val x1 = l.getHorizontalPosition(a, true)
            val x2 = l.getHorizontalPosition(z, true)
            out += Rect(minOf(x1, x2), l.getLineTop(line), maxOf(x1, x2), l.getLineBottom(line))
        }
        return out
    }

    companion object {
        /** Page badge size (design px) beside a page's last verse marker. */
        fun badgeW(th: QTheme) = (th.markerSize * (if (th.deco == Deco.GOLD) 1.12f else 0.92f)).roundToInt()
        fun badgeH(th: QTheme) = th.markerSize * 0.58f
        /** Room for the page number inside the badge (design px). */
        fun badgeInnerW(th: QTheme) = badgeW(th) - (if (th.deco == Deco.GOLD) badgeH(th) * 0.62f + 10f else 12f)
    }
}
