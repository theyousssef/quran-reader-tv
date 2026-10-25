package com.prayerhome.quran.ui

import android.util.Log
import android.view.KeyEvent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.appendInlineContent
import com.prayerhome.quran.App
import com.prayerhome.quran.audio.HomeBridge
import com.prayerhome.quran.data.Quran
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The reading column is a list of surah headers and page-sized blocks of flowing verses. */
sealed class Item(val surah: Int) {
    class Header(surah: Int) : Item(surah)
    /** Verses [from]..[to] (global indices, inclusive) of one surah on one mushaf page. */
    class Block(surah: Int, val from: Int, val to: Int) : Item(surah)
}

/** A block's text: verses joined with inline placeholders for the verse-end markers. */
class BlockText(val text: AnnotatedString, val starts: IntArray, val ends: IntArray)

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
        val starts = IntArray(n); val ends = IntArray(n)
        val t = buildAnnotatedString {
            for (k in 0 until n) {
                val v = b.from + k
                starts[k] = length
                append(quran.text(v))
                ends[k] = length
                append(' ') // keep the marker on the verse's last line
                appendInlineContent(v.toString(), "۝" + Quran.arabicNumber(v - Quran.surahStart(b.surah) + 1))
                if (k < n - 1) append(' ')
            }
        }
        BlockText(t, starts, ends)
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
    var bookmark by mutableIntStateOf(prefs.bookmark)
        private set
    var continueVerse by mutableIntStateOf(prefs.continueVerse.takeIf { it >= 0 } ?: prefs.topVerse)
        private set
    var toast by mutableStateOf<String?>(null)
        private set
    private var toastJob: Job? = null

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

    val list = LazyListState(prefs.scrollItem.coerceIn(0, items.size - 1).takeIf { prefs.scrollItem >= 0 } ?: 0, prefs.scrollOffset.coerceAtLeast(0))
    val layouts = HashMap<Int, TextLayoutResult>()
    val coords = HashMap<Int, LayoutCoordinates>()
    var pxPerDp = 1f

    val textFocus = FocusRequester()
    val toolFocus = FocusRequester()
    val playFocus = FocusRequester()
    val menuFocus = FocusRequester()

    // ------------------------------------------------------------ reading position

    fun updateTop() {
        val info = list.layoutInfo
        val vis = info.visibleItemsInfo
        if (vis.isEmpty()) return
        val vpH = info.viewportEndOffset - info.viewportStartOffset
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
            val l = layouts[it.index] ?: continue
            val bt = blockText(it.index)
            for (k in bt.starts.indices) {
                val y = it.offset + l.getLineTop(l.getLineForOffset(bt.starts[k]))
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

    /** Leaving the app: "Continue reading" now leads here. */
    fun onLeave() {
        savePosition()
        continueVerse = topVerse
        prefs.continueVerse = topVerse
    }

    private val topSafe get() = 96f * pxPerDp

    /** Vertical extent of a verse in viewport pixels, if its block is laid out and visible. */
    private fun verseSpan(v: Int): Pair<Float, Float>? {
        val bi = blockOf[v]
        val it = list.layoutInfo.visibleItemsInfo.firstOrNull { x -> x.index == bi } ?: return null
        val l = layouts[bi] ?: return null
        val bt = blockText(bi)
        val k = v - (items[bi] as Item.Block).from
        val top = l.getLineTop(l.getLineForOffset(bt.starts[k]))
        val bottom = l.getLineBottom(l.getLineForOffset((bt.ends[k] - 1).coerceAtLeast(bt.starts[k])))
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
    suspend fun reveal(v: Int, basmalaOf: Int = 0) {
        val vpH = (list.layoutInfo.viewportEndOffset - list.layoutInfo.viewportStartOffset).toFloat()
        if (basmalaOf > 0) {
            val hi = headerOf[basmalaOf]
            val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == hi }
            if (info != null && info.offset >= 0 && info.offset + info.size <= vpH) return
            if (info != null) list.animateScrollBy(info.offset.toFloat(), tween(450)) else list.scrollToItem(hi)
            return
        }
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
        scope.launch { list.animateScrollBy(dir * step, tween(220)) }
    }

    // ------------------------------------------------------------ recitation link

    fun onRecitation(active: Boolean, surah: Int, ayah: Int, basmala: Boolean) {
        if (!active) { recIdx = -1; recBasmala = 0; return }
        if (basmala) { recIdx = -1; recBasmala = surah } else { recIdx = Quran.indexOf(surah, ayah); recBasmala = 0 }
        val v = if (basmala) Quran.surahStart(surah) else recIdx
        scope.launch { reveal(v, if (basmala) surah else 0) }
    }

    // ------------------------------------------------------------ zones and popups

    fun focusText() { zone = Zone.TEXT; requestLater(textFocus) }
    fun focusToolbar() { zone = Zone.TOOLBAR; requestLater(toolFocus) }
    fun focusPlayer() { zone = Zone.PLAYER; requestLater(playFocus) }

    fun openMenu(t: Int = 0) { tab = t; menuOpen = true; zone = Zone.MENU }
    fun closeMenu() { menuOpen = false; focusText() }

    fun openPopup(p: Popup) { popupFrom = zone; popup = p }
    fun closePopup() {
        val from = popupFrom
        popup = Popup.NONE
        when (from) {
            Zone.TOOLBAR -> focusToolbar()
            Zone.PLAYER -> focusPlayer()
            else -> focusText()
        }
    }

    private fun requestLater(r: FocusRequester) {
        scope.launch { frames(1); runCatching { r.requestFocus() } }
    }

    fun showToast(msg: String) {
        toast = msg
        toastJob?.cancel()
        toastJob = scope.launch { delay(2200); toast = null }
    }

    // ------------------------------------------------------------ actions

    fun open(v: Int) {
        menuOpen = false
        marked = -1
        focusText()
        scope.launch { goTo(v); frames(1); updateTop() }
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
        showToast("Saved ${Quran.nameEn(r.surah)} · verse ${r.ayah}")
    }

    /** Back, from the innermost layer out; the last step leaves the app. */
    fun back() {
        when {
            popup != Popup.NONE -> closePopup()
            menuOpen -> closeMenu()
            zone == Zone.TOOLBAR || zone == Zone.PLAYER -> focusText()
            marked >= 0 -> marked = -1
            else -> {
                val s = rec.state.value
                if (s.active && s.playing && !HomeBridge.prayerHomeIsHome(app)) openPopup(Popup.EXIT)
                else exit()
            }
        }
    }

    /** The exit prompt (only without Prayer Home as the Home app). */
    fun exitKeepPlaying() { popup = Popup.NONE; HomeBridge.keepPlayingOnExit = true; exit() }
    fun exitPause() { popup = Popup.NONE; rec.pause(); exit() }

    /** Keys while the reading column has focus. */
    fun onReaderKey(code: Int, down: Boolean): Boolean {
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

    /** Rectangles (block-local) covering a verse's text, one per line. */
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
            var z = minOf(e, l.getLineEnd(line, visibleEnd = true))
            if (z <= a) continue
            val x1 = l.getHorizontalPosition(a, true)
            val x2 = l.getHorizontalPosition(z, true)
            out += Rect(minOf(x1, x2), l.getLineTop(line), maxOf(x1, x2), l.getLineBottom(line))
        }
        return out
    }
}
