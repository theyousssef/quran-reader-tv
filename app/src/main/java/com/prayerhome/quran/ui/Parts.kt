package com.prayerhome.quran.ui

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.prayerhome.quran.audio.PlaybackService
import com.prayerhome.quran.audio.Reciters
import com.prayerhome.quran.data.Quran
import kotlinx.coroutines.launch

private suspend fun frames(n: Int) = repeat(n) { withFrameNanos { } }

/** Handle one key (down and up) in a container; [onDown] returns whether the key is consumed. */
private fun Modifier.keys(onDown: (Int) -> Boolean): Modifier = onPreviewKeyEvent { e ->
    val code = e.key.nativeKeyCode
    when (e.type) {
        KeyEventType.KeyDown -> onDown(code)
        KeyEventType.KeyUp -> onDown(-code) // negative = key-up probe
        else -> false
    }
}

private fun Modifier.rightLine(color: Color) = drawBehind { drawRect(color, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height)) }

// ================================================================ side menu (expanded)

@Composable
fun SidePanel(m: ReaderModel, f: Frame) {
    val th = m.theme
    val continueReq = remember { FocusRequester() }
    // An invisible stop that holds focus while a tab's list is being replaced, so focus never lands on
    // (and flashes) the bookmark card on the way from one list to the next. It can only take focus while
    // parking (parked[0]); otherwise ▲ / ▼ would step onto it and focus would vanish.
    val holder = remember { FocusRequester() }
    val parked = remember { booleanArrayOf(true) }
    val onCard = remember { booleanArrayOf(false) }
    // Set by the current list: moves focus back to the row last focused in it.
    val toList = remember { arrayOfNulls<() -> Unit>(1) }
    LaunchedEffect(Unit) { runCatching { holder.requestFocus() } }
    fun switchTab(t: Int) {
        parked[0] = true
        runCatching { holder.requestFocus() }
        m.tab = t
    }
    val pad = when (th.deco) { Deco.AMBER -> listOf(44, 36, 30); Deco.INDIGO -> listOf(44, 34, 30); Deco.GOLD -> listOf(38, 30, 26) }
    Column(
        Modifier
            .size(th.sideW.dp, 1080.dp)
            .background(th.panel)
            .rightLine(th.line)
            .padding(start = pad[1].dp, end = pad[1].dp, top = pad[0].dp, bottom = pad[2].dp)
            .keys { code ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // On the card, ▶ goes back down into the list; in a list, Surah → Juz → close.
                        if (onCard[0]) toList[0]?.invoke()
                        else if (m.tab < TAB_NAMES.size - 1) switchTab(m.tab + 1)
                        else m.closeMenu()
                        true
                    }
                    // ◀ in the Surah list goes to "Continue from bookmark"; in the Juz list (or on the card while
                    // on Juz) it goes back to the Surah tab, without passing the card.
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (m.tab > 0) switchTab(m.tab - 1)
                        else if (!onCard[0]) runCatching { continueReq.requestFocus() }
                        true
                    }
                    -KeyEvent.KEYCODE_DPAD_RIGHT, -KeyEvent.KEYCODE_DPAD_LEFT -> true
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(if (th.deco == Deco.GOLD) 18.dp else 20.dp),
    ) {
        PanelHeader(th)
        ContinueCard(m, continueReq, Modifier.onFocusChanged { onCard[0] = it.hasFocus })
        Box {
            Tabs(th, m.tab)
            Box(
                Modifier.size(1.dp)
                    .onFocusChanged { if (!it.isFocused) parked[0] = false }
                    .focusProperties { canFocus = parked[0] }
                    .focusRequester(holder).focusable(),
            )
        }
        key(m.tab) { MenuList(m, continueReq, toList) }
        T("▲▼ choose · OK open · ▶ next tab · Back close", th.ui, if (th.deco == Deco.GOLD) 15 else 16, th.muted)
    }
}

@Composable
private fun PanelHeader(th: QTheme) {
    when (th.deco) {
        Deco.AMBER, Deco.INDIGO -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (th.deco == Deco.AMBER) {
                Canvas(Modifier.size(40.dp)) {
                    octagram(40f, 8f, th.accent, 1.6f)
                    drawCircle(th.accent, 5f * size.width / 40f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.6f * size.width / 40f))
                }
            } else Canvas(Modifier.size(38.dp)) { octagram(38f, 9f, th.gold, 1.5f) }
            T(if (th.deco == Deco.AMBER) "Quran" else "The Quran", th.display, 30, th.text, Modifier.weight(1f), weight = 500)
            Ar("القرآن الكريم", th.arUi, if (th.deco == Deco.AMBER) 30 else 28, th.arAccent, weight = th.arUiWeight)
            Box(Modifier.padding(start = 6.dp).size(52.dp).clip(RoundedCornerShape(14.dp)).background(th.bg).border(1.dp, th.line, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(Ico.PANEL_CLOSE, th.accent, 24)
            }
        }
        Deco.GOLD -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Ar("القرآن الكريم", Fonts.ArefRuqaa, 46, th.arAccent, weight = 700, lineHeight = 1.45f)
                T("The Holy Quran", Fonts.Lora, 21, th.muted, weight = 500, italic = true)
            }
            Box(Modifier.padding(top = 10.dp).size(54.dp).clip(CircleShape).background(th.bg).border(1.dp, th.gold, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Ico.PANEL_CLOSE, th.arAccent, 22)
            }
        }
    }
}

/** "Continue from bookmark": opens the bookmarked verse and lights it up (and moves a recitation there). */
@Composable
private fun ContinueCard(m: ReaderModel, fr: FocusRequester, modifier: Modifier = Modifier) {
    val th = m.theme
    val v = m.bookmark
    val r = Quran.refOf(v)
    Focusable(modifier, onClick = { m.openBookmark() }, focusRequester = fr) { focused ->
        val shape = RoundedCornerShape((if (th.deco == Deco.GOLD) 20 else 18).dp)
        val base = Modifier.fillMaxWidth().clip(shape)
            .background(if (th.deco == Deco.GOLD) th.bg else th.card, shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) th.accent else (if (th.deco == Deco.GOLD) th.line else th.border), shape)
        val ic = if (th.deco == Deco.GOLD) th.arAccent else th.accent
        if (th.deco == Deco.GOLD) {
            Row(base.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(th.card).border(1.5.dp, th.fill, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Ico.BOOKMARK, ic, 28, stroke = 2f)
                }
                Column(Modifier.weight(1f)) {
                    T("Continue from bookmark", Fonts.Lora, 21, th.text, weight = 600)
                    T("${Quran.nameEn(r.surah)} · Verse ${r.ayah} · Juz ${Quran.juzOf(v)} of 30", th.ui, 15, th.muted)
                }
                Icon(Ico.CHEVRON_RIGHT, th.arAccent, 22, stroke = 2f)
            }
        } else {
            Row(base.padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Ico.BOOKMARK, ic, 30, stroke = 2f)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    T("CONTINUE FROM BOOKMARK", th.ui, 15, th.accent, spacing = 0.14f)
                    T("${Quran.nameEn(r.surah)} · Verse ${r.ayah}", th.ui, 24, th.text)
                }
                Ar(Quran.nameAr(r.surah), th.arUi, 28, th.arAccent, weight = th.arUiWeight)
                Icon(Ico.CHEVRON_RIGHT, th.arAccent, 22, stroke = 2f)
            }
        }
    }
}

private val TAB_NAMES = listOf("Surah", "Juz")

@Composable
private fun Tabs(th: QTheme, tab: Int) {
    when (th.deco) {
        Deco.AMBER -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(th.bg).border(1.dp, th.line, RoundedCornerShape(999.dp)).padding(4.dp)) {
            TAB_NAMES.forEachIndexed { i, n ->
                val on = i == tab
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(999.dp)).background(if (on) th.accent else Color.Transparent), contentAlignment = Alignment.Center) {
                    T(n, th.ui, 20, if (on) th.onAccent else th.muted, weight = if (on) 600 else 400)
                }
            }
        }
        Deco.INDIGO -> Row(Modifier.fillMaxWidth().drawBehind { drawRect(th.line, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            TAB_NAMES.forEachIndexed { i, n ->
                val on = i == tab
                Column {
                    T(if (i == 0) "Surahs" else n, th.ui, 21, if (on) th.text else th.muted, weight = if (on) 500 else 400)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.width(if (i == 0) 84.dp else 36.dp).height(3.dp).background(if (on) th.accent else Color.Transparent))
                }
            }
        }
        Deco.GOLD -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TAB_NAMES.forEachIndexed { i, n ->
                val on = i == tab
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) th.card else Color.Transparent)
                        .border(1.5.dp, if (on) th.fill else Color.Transparent, RoundedCornerShape(999.dp))
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) { T(n, th.ui, 17, if (on) th.text else th.muted, weight = if (on) 600 else 400) }
            }
        }
    }
}

@Composable
private fun ColumnScope.MenuList(m: ReaderModel, continueReq: FocusRequester, toList: Array<(() -> Unit)?>) {
    val th = m.theme
    val state = rememberLazyListState()
    val reqs = remember { HashMap<Int, FocusRequester>() }
    val tab = m.tab
    val count = if (tab == 0) 114 else 30
    val target = if (tab == 0) m.currentSurah - 1 else Quran.juzOf(m.progressVerse) - 1
    val last = remember { intArrayOf(target) }
    val scope = rememberCoroutineScope()
    toList[0] = {
        val r = reqs[last[0]]
        if (r != null) runCatching { r.requestFocus() }
        else scope.launch {
            state.scrollToItem((last[0] - 3).coerceAtLeast(0))
            var n = 0
            while (reqs[last[0]] == null && n++ < 10) frames(1)
            reqs[last[0]]?.let { runCatching { it.requestFocus() } }
        }
    }
    LaunchedEffect(Unit) {
        if (count > 0) state.scrollToItem((target - 3).coerceAtLeast(0))
        frames(2)
        var n = 0
        while (reqs[target] == null && n++ < 10) frames(1)
        runCatching { (reqs[target] ?: continueReq).requestFocus() }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, verticalArrangement = Arrangement.spacedBy(when (th.deco) { Deco.AMBER -> 0.dp; Deco.INDIGO -> 4.dp; Deco.GOLD -> 3.dp })) {
        items(count) { i ->
            val fr = remember { FocusRequester() }
            DisposableEffect(i) { reqs[i] = fr; onDispose { if (reqs[i] === fr) reqs.remove(i) } }
            when (tab) {
                0 -> {
                    val s = i + 1
                    MenuRow(th, s, "$s", Quran.nameEn(s), "${Quran.ayahs(s)} verses · ${if (Quran.meccan(s)) "Meccan" else "Medinan"}", Quran.nameAr(s), s == m.currentSurah, fr, Modifier.onFocusChanged { if (it.isFocused) last[0] = i }) {
                        m.open(Quran.surahStart(s))
                    }
                }
                else -> {
                    val j = i + 1
                    val st = Quran.juzStart(j)
                    val idx = st.index
                    MenuRow(th, j, "$j", "Juz $j", "${Quran.nameEn(st.surah)} ${st.ayah} · page ${Quran.pageOf(idx)}", Quran.juzNames[i], j == Quran.juzOf(m.progressVerse), fr, Modifier.onFocusChanged { if (it.isFocused) last[0] = i }) {
                        m.openJuz(j)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(th: QTheme, n: Int, num: String, title: String, meta: String, arabic: String, current: Boolean, fr: FocusRequester, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(modifier, onClick = onClick, focusRequester = fr) { focused ->
        val r = when (th.deco) { Deco.AMBER, Deco.INDIGO -> 14; Deco.GOLD -> 16 }
        val shape = RoundedCornerShape(r.dp)
        val h = when (th.deco) { Deco.AMBER -> 70; Deco.INDIGO -> 66; Deco.GOLD -> 68 }
        val hi = focused || current
        Row(
            Modifier.fillMaxWidth().height(h.dp).clip(shape)
                .background(if (hi) th.card else Color.Transparent, shape)
                .border(if (focused) 2.dp else 1.5.dp, if (focused) th.accent else if (current && th.deco == Deco.GOLD) th.fill else Color.Transparent, shape)
                .padding(start = (if (th.deco == Deco.GOLD) 10 else 16).dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((if (th.deco == Deco.GOLD) 14 else 16).dp),
        ) {
            when (th.deco) {
                Deco.AMBER -> Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) { octagram(42f, 8f, if (hi) th.accent else th.badgeStroke) }
                    T(num, th.ui, 15, if (hi) th.accent else th.badgeText, weight = 500)
                }
                Deco.INDIGO -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) { diamond(40f, 8f, 3f, if (hi) th.accent else th.badgeStroke) }
                    T(num, th.ui, 15, if (hi) c(0xF2F5FA) else th.badgeText)
                }
                Deco.GOLD -> Box(
                    Modifier.size(42.dp).clip(CircleShape).background(if (hi) th.accent else Color.Transparent).border(1.5.dp, if (hi) th.accent else th.badgeStroke, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { T(num, th.ui, 15, if (hi) th.onAccent else th.badgeText, weight = 600) }
            }
            Column(Modifier.weight(1f)) {
                if (th.deco == Deco.GOLD) T(title, Fonts.Lora, 21, th.text, weight = 600)
                else T(title, th.ui, if (th.deco == Deco.AMBER) 21 else 20, th.text)
                T(meta, th.ui, if (th.deco == Deco.AMBER) 15 else 14, th.muted)
            }
            Ar(arabic, th.arUi, if (th.deco == Deco.INDIGO) 28 else 30, if (hi) th.arAccent.takeIf { th.deco != Deco.INDIGO } ?: c(0xF2F5FA) else th.text, weight = th.arUiWeight.takeIf { th.deco != Deco.AMBER } ?: 400)
        }
    }
}

private fun c(v: Long) = Color(0xFF000000 or v)

// ================================================================ collapsed rail

@Composable
fun Rail(m: ReaderModel, f: Frame) {
    val th = m.theme
    val round = th.deco == Deco.GOLD
    val btn = if (round) 56 else 58
    val shape = if (round) CircleShape else RoundedCornerShape(14.dp)
    val iconColor = if (th.deco == Deco.GOLD) th.arAccent else th.accent
    val s = m.currentSurah
    Column(
        Modifier.size(th.railW.dp, 1080.dp).background(th.panel).rightLine(th.line)
            .padding(top = (if (round) 32 else 34).dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(btn.dp).clip(shape).background(th.bg).border(1.dp, if (round) th.gold else th.line, shape), contentAlignment = Alignment.Center) {
            Icon(Ico.PANEL_OPEN, iconColor, if (round) 22 else 24)
        }
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (th.deco) {
                Deco.AMBER -> Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) { octagram(58f, 12f, th.accent, 1.6f, th.card) }
                    T("$s", th.ui, 18, th.accent, weight = 600)
                }
                Deco.INDIGO -> Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) { diamond(58f, 12f, 4f, th.accent, 1.6f, th.card) }
                    T("$s", th.ui, 17, c(0xF2F5FA), weight = 600)
                }
                Deco.GOLD -> Box(Modifier.size(56.dp).clip(CircleShape).background(th.card).border(1.5.dp, th.fill, CircleShape), contentAlignment = Alignment.Center) {
                    Ar(Quran.arabicNumber(s), m.font.family, 24, th.arAccent, weight = 700)
                }
            }
            Ar(Quran.nameAr(s), th.arUi, if (round) 26 else 22, if (th.deco == Deco.INDIGO) th.gold else th.arAccent, weight = th.arUiWeight, lineHeight = if (round) 1.5f else 0f)
        }
        // Display-only like the rest of the rail (focus never lands here): bookmark, Surah list, Juz list.
        Box(Modifier.size(btn.dp), contentAlignment = Alignment.Center) {
            Icon(Ico.BOOKMARK, th.muted, if (round) 22 else 24)
        }
        for ((i, ico) in listOf(Ico.LIST, Ico.LAYERS).withIndex()) {
            val on = i == 0
            Box(Modifier.size(btn.dp).clip(shape).background(if (on) th.card else Color.Transparent), contentAlignment = Alignment.Center) {
                Icon(ico, if (on) iconColor else th.muted, if (round) 22 else 24)
            }
        }
        if (round) {
            val frac = (m.topVerse + 1f) / Quran.VERSES
            Box(Modifier.padding(top = 6.dp).size(56.dp), contentAlignment = Alignment.Center) {
                Ring(56, 4f, th.track, th.fill, frac)
                T("${(frac * 100).toInt()}%", th.ui, 13, th.arAccent, weight = 600)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(btn.dp), contentAlignment = Alignment.Center) { Icon(Ico.HOME, th.muted, if (round) 22 else 24) }
    }
}

// ================================================================ toolbar

@Composable
fun Toolbar(m: ReaderModel, f: Frame) {
    val th = m.theme
    val v = m.progressVerse
    val rs by m.rec.state.collectAsState()
    Row(
        Modifier.offset(f.contentX.dp, f.toolbarY.dp).size(f.contentW.dp, 52.dp)
            .keys { code ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (rs.active) m.focusPlayer() else m.focusText(); true }
                    KeyEvent.KEYCODE_DPAD_UP, -KeyEvent.KEYCODE_DPAD_DOWN, -KeyEvent.KEYCODE_DPAD_UP -> true
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (th.deco == Deco.GOLD) 16.dp else 18.dp)) {
            if (th.deco == Deco.GOLD) T(Quran.nameEn(m.currentSurah), Fonts.Lora, 29, th.text, weight = 700)
            else T(Quran.nameEn(m.currentSurah), th.display, 26, th.text, weight = 500)
            if (th.deco == Deco.AMBER) Box(Modifier.size(1.dp, 26.dp).background(th.line))
            T("Juz ${Quran.juzOf(v)} · Page ${Quran.pageOf(v)} of ${Quran.PAGES}", th.ui, when (th.deco) { Deco.AMBER -> 20; Deco.INDIGO -> 19; Deco.GOLD -> 18 }, th.muted)
            if (m.recMode) RecModeBanner(m)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (th.deco == Deco.GOLD) 10.dp else 12.dp)) {
            // Left from the first button goes back down, like ▼ (a second Left then opens the menu as usual).
            ToolButton(th, fr = m.toolFocus, radius = if (th.deco == Deco.INDIGO) 999 else th.toolRadius, onLeft = { m.focusHome() }, onClick = { m.openPopup(Popup.FONT) }) {
                Ar("ع", m.font.family, if (th.deco == Deco.INDIGO) 30 else 26, th.arAccent, lineHeight = 1f)
                T("Quran font", th.ui, if (th.deco == Deco.GOLD) 16 else 17, th.text, weight = if (th.deco == Deco.GOLD) 500 else 400)
            }
            ToolButton(th, width = if (th.deco == Deco.AMBER) 54 else 50, onClick = { m.textSmaller() }) {
                T("A−", th.ui, if (th.deco == Deco.GOLD) 16 else 17, th.text)
            }
            ToolButton(th, width = if (th.deco == Deco.AMBER) 54 else 50, onClick = { m.textLarger() }) {
                T("A+", th.ui, if (th.deco == Deco.GOLD) 20 else 21, th.text)
            }
            // In recitation mode the reciter is chosen from the player bar instead.
            if (!m.recMode) ToolButton(th, onClick = { m.openPopup(Popup.RECITER) }) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(th.monoBg), contentAlignment = Alignment.Center) {
                    T(rs.reciter.mono, if (th.deco == Deco.GOLD) Fonts.Lora else th.ui, 13, th.monoFg, weight = 700)
                }
                T(rs.reciter.short, th.ui, if (th.deco == Deco.GOLD) 16 else 17, th.text)
            }
            ToolButton(th, onClick = { m.openPopup(Popup.THEME) }) {
                Icon(Ico.PALETTE, th.arAccent, 20)
                T("Theme", th.ui, if (th.deco == Deco.GOLD) 16 else 17, th.text)
            }
        }
    }
}

@Composable
private fun ToolButton(th: QTheme, width: Int = 0, radius: Int = th.toolRadius, fr: FocusRequester? = null, onLeft: (() -> Unit)? = null, onClick: () -> Unit, content: @Composable () -> Unit) {
    val mod = if (onLeft == null) Modifier else Modifier.keys { code ->
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { onLeft(); true }
            -KeyEvent.KEYCODE_DPAD_LEFT -> true
            else -> false
        }
    }
    Focusable(mod, onClick = onClick, focusRequester = fr) { focused ->
        val shape = RoundedCornerShape(radius.dp)
        Row(
            Modifier.height(46.dp).let { if (width > 0) it.width(width.dp) else it }.clip(shape)
                .background(if (focused) th.card else Color.Transparent, shape)
                .border(if (focused) 2.dp else 1.dp, if (focused) th.accent else th.line, shape)
                .padding(horizontal = if (width > 0) 0.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) { content() }
    }
}

/** "——— In Audio Recitation mode · CLICK TO EXIT ———": OK stops the recitation, hides the player and deletes its files. */
@Composable
private fun RecModeBanner(m: ReaderModel) {
    val th = m.theme
    val mod = Modifier.padding(start = 6.dp).keys { code -> code == KeyEvent.KEYCODE_DPAD_LEFT || code == -KeyEvent.KEYCODE_DPAD_LEFT }
    Focusable(mod, onClick = { m.exitRecitation() }, focusRequester = m.bannerFocus) { fo ->
        val sh = RoundedCornerShape(if (th.deco == Deco.GOLD) 999.dp else 12.dp)
        val fg = if (fo) th.onAccent else (if (th.deco == Deco.GOLD) th.arAccent else th.accent)
        Row(
            Modifier.height(46.dp).clip(sh).background(if (fo) th.accent else th.card, sh)
                .border(if (fo) 2.dp else 1.dp, if (fo) th.text else th.border, sh).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BannerRule(th, fg, true)
            T("In Audio Recitation mode", if (th.deco == Deco.GOLD) Fonts.Lora else th.ui, if (th.deco == Deco.GOLD) 19 else 18, if (fo) th.onAccent else th.text, weight = if (th.deco == Deco.GOLD) 700 else 500, italic = th.deco == Deco.GOLD)
            T("·", th.ui, 18, fg)
            T("CLICK TO EXIT", th.ui, 15, fg, weight = 700, spacing = 0.12f)
            BannerRule(th, fg, false)
        }
    }
}

/** A short rule ending in the theme's ornament, pointing at the banner's text. */
@Composable
private fun BannerRule(th: QTheme, color: Color, leading: Boolean) {
    Canvas(Modifier.size(34.dp, 12.dp)) {
        val y = size.height / 2
        val o = size.height
        val ox = if (leading) size.width - o else 0f
        val lx0 = if (leading) 0f else o + 2.dp.toPx()
        val lx1 = if (leading) size.width - o - 2.dp.toPx() else size.width
        drawRect(color, Offset(lx0, y - 0.75.dp.toPx()), Size(lx1 - lx0, 1.5.dp.toPx()))
        translate(ox, 0f) {
            when (th.deco) {
                Deco.AMBER -> octagram(12f, 2.5f, color, 1.4f, side = o)
                Deco.INDIGO -> diamond(12f, 2f, 1f, color, 1.4f, color, side = o)
                Deco.GOLD -> drawCircle(color, o * 0.28f, Offset(o / 2, y))
            }
        }
    }
}

// ================================================================ recitation bar

/**
 * The player bar of Audio Recitation mode: play/pause, previous, next, the clip's progress, repeat and the
 * reciter (the place to change reciter while reciting). ◀/▶ move along it; ◀ on play/pause opens the menu;
 * ▲ goes to the exit banner.
 */
@Composable
fun PlayerBar(m: ReaderModel, f: Frame) {
    val th = m.theme
    val rs by m.rec.state.collectAsState()
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier.offset(f.contentX.dp, f.playerY.dp).size(f.contentW.dp, 104.dp).clip(shape).background(th.playerBg, shape).border(1.dp, th.line, shape)
            .padding(horizontal = 16.dp)
            .keys { code ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> { m.focusBanner(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN, -KeyEvent.KEYCODE_DPAD_UP, -KeyEvent.KEYCODE_DPAD_DOWN -> true
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val playMod = Modifier
            .onFocusChanged { m.playFocused = it.isFocused }
            .keys { code ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { m.openMenu(0); true }
                    -KeyEvent.KEYCODE_DPAD_LEFT -> true
                    else -> false
                }
            }
        Focusable(playMod, onClick = { m.rec.togglePause() }, focusRequester = m.playFocus) { fo ->
            Box(Modifier.size(74.dp).ring(fo, th.text).clip(CircleShape).background(th.accent), contentAlignment = Alignment.Center) {
                Glyph(if (rs.playing) Ico.PAUSE else Ico.PLAY, th.onAccent, 30)
            }
        }
        RoundButton(th, Ico.PREV) { m.rec.previous() }
        RoundButton(th, Ico.NEXT) { m.rec.next() }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                val what = if (rs.basmala) "basmala" else "verse ${rs.ayah}"
                val status = when {
                    rs.buffering && rs.playing -> rs.error ?: "Loading $what…"
                    rs.playing -> "Now reciting · ${Quran.nameEn(rs.surah)} · $what"
                    else -> "Paused · ${Quran.nameEn(rs.surah)} · $what"
                }
                T(if (th.deco == Deco.GOLD) status.uppercase() else status, th.ui, if (th.deco == Deco.GOLD) 17 else 18, if (rs.playing) th.arAccent.takeIf { th.deco == Deco.GOLD } ?: th.accent else th.muted, Modifier.weight(1f), weight = if (th.deco == Deco.GOLD) 600 else 500)
                T("${clock(m.clipSecond * 1000)} / ${clock(m.clipSeconds * 1000)}", th.ui, if (th.deco == Deco.GOLD) 17 else 18, th.muted)
            }
            // Drawn from the per-frame progress in the draw phase only: it glides without recomposing the bar.
            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                val frac = m.clipProgress.floatValue.coerceIn(0f, 1f)
                val h = 6.dp.toPx()
                val y = (size.height - h) / 2
                drawRoundRect(th.track, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                if (frac > 0f) drawRoundRect(th.fill, Offset(0f, y), Size(size.width * frac, h), CornerRadius(h / 2))
                drawCircle(th.knob, 8.dp.toPx(), Offset(size.width * frac, size.height / 2))
            }
        }
        Focusable(onClick = { m.rec.toggleRepeat() }) { fo ->
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (rs.repeat) th.card else Color.Transparent).border(if (fo) 2.dp else 0.dp, if (fo) th.accent else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Ico.REPEAT, if (rs.repeat) th.accent else th.muted, 22)
            }
        }
        val lastMod = Modifier.keys { code -> code == KeyEvent.KEYCODE_DPAD_RIGHT || code == -KeyEvent.KEYCODE_DPAD_RIGHT }
        Focusable(lastMod, onClick = { m.openPopup(Popup.RECITER) }) { fo ->
            val sh = RoundedCornerShape(999.dp)
            Row(
                Modifier.height(72.dp).clip(sh).background(th.card).border(2.dp, if (fo || m.popup == Popup.RECITER) th.accent else Color.Transparent, sh).padding(start = 8.dp, end = 18.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(th.monoBg), contentAlignment = Alignment.Center) {
                    if (th.deco == Deco.GOLD) T(rs.reciter.mono, Fonts.Lora, 20, th.monoFg, weight = 700)
                    else T(rs.reciter.mono, th.ui, if (th.deco == Deco.AMBER) 19 else 18, th.monoFg, weight = 600)
                }
                Column {
                    if (th.deco == Deco.GOLD) T(rs.reciter.en, Fonts.Lora, 20, th.text, weight = 700)
                    else T(rs.reciter.en, th.ui, if (th.deco == Deco.AMBER) 20 else 19, th.text, weight = 500)
                    T("${rs.reciter.style} · change", th.ui, if (th.deco == Deco.AMBER) 15 else 14, th.muted)
                }
                Icon(Ico.CHEVRON_UP, th.arAccent.takeIf { th.deco != Deco.INDIGO } ?: th.accent, 20, stroke = 2f)
            }
        }
    }
}

private fun clock(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

private fun Modifier.ring(on: Boolean, color: Color) = if (!on) this else this.border(3.dp, color, CircleShape).padding(5.dp)

@Composable
private fun RoundButton(th: QTheme, glyph: String, onClick: () -> Unit) {
    Focusable(onClick = onClick) { fo ->
        Box(Modifier.size(52.dp).clip(CircleShape).background(th.card).border(if (fo) 2.dp else 0.dp, if (fo) th.accent else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
            Glyph(glyph, th.iconOnCard, 22)
        }
    }
}

// ================================================================ popups

@Composable
private fun PopupBox(th: QTheme, modifier: Modifier, title: String, arabic: String, hint: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape((if (th.deco == Deco.GOLD) 24 else 22).dp)
    Column(
        modifier.clip(shape).background(th.popup, shape).border(1.dp, th.border, shape).padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 18.dp)
            .keys { code -> code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == -KeyEvent.KEYCODE_DPAD_LEFT || code == -KeyEvent.KEYCODE_DPAD_RIGHT },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (th.deco == Deco.GOLD) T(title, Fonts.Lora, 25, th.text, Modifier.weight(1f), weight = 700)
            else T(title, th.ui, 24, th.text, Modifier.weight(1f), weight = 500)
            Ar(arabic, th.arUi, if (th.deco == Deco.GOLD) 28 else 24, th.arAccent, weight = th.arUiWeight)
        }
        content()
        T(hint, th.ui, 15, th.muted, Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp))
    }
}

/** Focus the row at [index] once the popup is composed. */
@Composable
private fun rowRequesters(n: Int, index: Int): List<FocusRequester> {
    val reqs = remember(n) { List(n) { FocusRequester() } }
    LaunchedEffect(Unit) { frames(1); runCatching { reqs[index.coerceIn(0, n - 1)].requestFocus() } }
    return reqs
}

@Composable
private fun PopupRow(th: QTheme, height: Int, selected: Boolean, fr: FocusRequester, onClick: () -> Unit, content: @Composable () -> Unit) {
    Focusable(onClick = onClick, focusRequester = fr) { fo ->
        val shape = RoundedCornerShape((if (th.deco == Deco.GOLD) 16 else 14).dp)
        Row(
            Modifier.fillMaxWidth().height(height.dp).clip(shape).background(if (selected) th.card else Color.Transparent, shape)
                .border(2.dp, if (fo) th.accent else Color.Transparent, shape).padding(start = 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(Ico.CHECK, th.arAccent.takeIf { th.deco != Deco.INDIGO } ?: th.accent, 22, stroke = 2.4f)
            }
        }
    }
}

@Composable
fun FontPopup(m: ReaderModel, f: Frame) {
    val th = m.theme
    val reqs = rowRequesters(QURAN_FONTS.size, m.fontIndex)
    Box(Modifier.fillMaxSize()) {
        PopupBox(th, Modifier.align(Alignment.TopEnd).padding(end = (f.padR + 120).dp, top = 100.dp).width(720.dp), "Quran font", "خط المصحف", "Every font here shows the full Uthmani script · OK select · Back close") {
            QURAN_FONTS.forEachIndexed { i, qf ->
                PopupRow(th, 76, i == m.fontIndex, reqs[i], onClick = { m.setFont(i); m.closePopup() }) {
                    Column(Modifier.width(200.dp)) {
                        if (th.deco == Deco.GOLD) T(qf.name, Fonts.Lora, 20, th.text, weight = 700) else T(qf.name, th.ui, 19, th.text, weight = 500)
                        T(qf.desc, th.ui, 14, th.muted)
                    }
                    Ar(m.quran.basmala, qf.family, 28, th.text, Modifier.weight(1f), lineHeight = 1.9f, align = TextAlign.Right)
                }
            }
        }
    }
}

@Composable
fun ReciterPopup(m: ReaderModel, f: Frame) {
    val th = m.theme
    val rs by m.rec.state.collectAsState()
    val list = Reciters.all
    val sel = list.indexOf(rs.reciter).coerceAtLeast(0)
    val reqs = rowRequesters(list.size, sel)
    val fromPlayer = m.zone == Zone.PLAYER
    Box(Modifier.fillMaxSize()) {
        val pos = if (fromPlayer) Modifier.align(Alignment.BottomEnd).padding(end = f.padR.dp, bottom = 150.dp)
        else Modifier.align(Alignment.TopEnd).padding(end = f.padR.dp, top = 100.dp)
        PopupBox(th, pos.width(560.dp), "Choose reciter", "اختر القارئ", "▲ ▼ choose · OK select · Back close") {
            list.forEachIndexed { i, r ->
                PopupRow(th, 66, i == sel, reqs[i], onClick = { m.rec.setReciter(r); m.closePopup() }) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(if (i == sel) th.monoBg else th.line), contentAlignment = Alignment.Center) {
                        T(r.mono, if (th.deco == Deco.GOLD) Fonts.Lora else th.ui, if (th.deco == Deco.GOLD) 17 else 16, if (i == sel) th.monoFg else th.arAccent, weight = if (th.deco == Deco.GOLD) 700 else 600)
                    }
                    Column(Modifier.weight(1f)) {
                        if (th.deco == Deco.GOLD) T(r.en, Fonts.Lora, 20, th.text, weight = 700) else T(r.en, th.ui, 19, th.text, weight = 500)
                        T(r.style, th.ui, 14, th.muted)
                    }
                    Ar(r.ar, th.arUi, if (th.deco == Deco.GOLD) 22 else 20, th.arAccent, weight = if (th.deco == Deco.AMBER) 400 else th.arUiWeight)
                }
            }
        }
    }
}

@Composable
fun ThemePopup(m: ReaderModel, f: Frame) {
    val th = m.theme
    val reqs = rowRequesters(THEMES.size, m.themeIndex)
    Box(Modifier.fillMaxSize()) {
        PopupBox(th, Modifier.align(Alignment.TopEnd).padding(end = f.padR.dp, top = 100.dp).width(600.dp), "Theme", "المظهر", "▲ ▼ choose · OK select · Back close") {
            THEMES.forEachIndexed { i, t ->
                PopupRow(th, 76, i == m.themeIndex, reqs[i], onClick = { m.setTheme(i); m.closePopup() }) {
                    Box(Modifier.size(56.dp, 40.dp).clip(RoundedCornerShape(10.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(t.accent))
                            Box(Modifier.size(12.dp).clip(CircleShape).background(t.text))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        if (th.deco == Deco.GOLD) T(t.name, Fonts.Lora, 20, th.text, weight = 700) else T(t.name, th.ui, 19, th.text, weight = 500)
                        T("${if (t.dark) "Dark" else "Light"} · ${QURAN_FONTS[t.pairedFont].name}", th.ui, 14, th.muted)
                    }
                }
            }
        }
    }
}

/** "Verse pressed": Recite from here / Bookmark verse. */
@Composable
fun VerseOptions(m: ReaderModel, f: Frame) {
    val th = m.theme
    val v = m.marked.coerceAtLeast(0)
    val r = Quran.refOf(v)
    val reqs = rowRequesters(2, 0)
    val shape = RoundedCornerShape(24.dp)
    val x = f.sideW + (1920 - f.sideW - 840) / 2
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomStart).offset(x.dp, (-36).dp).width(840.dp).clip(shape).background(th.popup, shape).border(1.dp, th.border, shape)
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 20.dp)
                .keys { code -> code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == -KeyEvent.KEYCODE_DPAD_UP || code == -KeyEvent.KEYCODE_DPAD_DOWN },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (th.deco == Deco.GOLD) T("${Quran.nameEn(r.surah)} · Verse ${r.ayah}", Fonts.Lora, 23, th.text, Modifier.weight(1f), weight = 600)
                else T("${Quran.nameEn(r.surah)} · Verse ${r.ayah}", th.ui, 26, th.text, Modifier.weight(1f), weight = 600)
                T("◀ ▶ choose · OK select · Back close", th.ui, 15, th.muted)
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (th.deco == Deco.GOLD) th.playerBg else th.card).padding(horizontal = 16.dp, vertical = 4.dp)) {
                Ar(m.quran.text(v), m.font.family, 30, th.text, Modifier.fillMaxWidth(), lineHeight = 1.9f, align = TextAlign.Right, maxLines = 3)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OptionButton(th, Modifier.weight(1f), reqs[0], true, Ico.PLAY, "Recite from here", "Player starts at this verse") { m.reciteMarked() }
                OptionButton(th, Modifier.weight(1f), reqs[1], false, Ico.BOOKMARK, "Bookmark verse", "Moves your bookmark here") { m.bookmarkMarked() }
            }
        }
    }
}

@Composable
private fun OptionButton(th: QTheme, modifier: Modifier, fr: FocusRequester, primary: Boolean, icon: String, title: String, sub: String, onClick: () -> Unit) {
    Focusable(modifier, onClick = onClick, focusRequester = fr) { fo ->
        val shape = RoundedCornerShape(18.dp)
        Row(
            Modifier.fillMaxWidth().height(96.dp).clip(shape).background(if (th.deco == Deco.GOLD) th.playerBg else th.card, shape)
                .border(2.dp, if (fo) th.accent else Color.Transparent, shape).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val ic = if (th.deco == Deco.GOLD) th.arAccent else th.accent
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(if (primary) th.accent else Color.Transparent)
                    .border(if (primary) 0.dp else 1.5.dp, if (primary) Color.Transparent else th.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (primary) Glyph(icon, th.onAccent, 22) else Icon(icon, ic, 22, stroke = 2f) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(title, th.ui, 19, th.text, weight = 600)
                T(sub, th.ui, 14, th.muted)
            }
        }
    }
}

/** Back or Home while reciting: keep playing on the home screen, or pause. Back here stays in the reader. */
@Composable
fun ExitPrompt(m: ReaderModel, f: Frame) {
    val th = m.theme
    val rs by m.rec.state.collectAsState()
    val reqs = rowRequesters(2, 0)
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxSize().background(th.scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(760.dp).clip(shape).background(th.popup, shape).border(1.dp, th.border, shape).padding(26.dp)
                .keys { code -> code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == -KeyEvent.KEYCODE_DPAD_UP || code == -KeyEvent.KEYCODE_DPAD_DOWN },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            T("Keep reciting in the background?", th.display, 28, th.text, weight = th.displayWeight)
            T("${PlaybackService.title(rs)} · ${rs.reciter.en}. The recitation can go on while you're on the home screen.", th.ui, 18, th.muted, maxLines = 3, lineHeight = 1.4f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OptionButton(th, Modifier.weight(1f), reqs[0], true, Ico.PLAY, "Keep playing", "Go to the home screen, keep listening") { m.exitKeepPlaying() }
                OptionButton(th, Modifier.weight(1f), reqs[1], false, Ico.PAUSE, "Pause", "Pause, then go to the home screen") { m.exitPause() }
            }
        }
    }
}

@Composable
fun Toast(th: QTheme, text: String, f: Frame, player: Boolean) {
    Box(Modifier.fillMaxSize().padding(bottom = (if (player) 1080 - f.playerY + 20 else 60).dp, start = f.sideW.dp), contentAlignment = Alignment.BottomCenter) {
        val shape = RoundedCornerShape(999.dp)
        Box(Modifier.clip(shape).background(th.popup, shape).border(1.dp, th.border, shape).padding(horizontal = 26.dp, vertical = 14.dp)) {
            T(text, th.ui, 18, th.text)
        }
    }
}
