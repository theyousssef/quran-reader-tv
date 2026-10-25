package com.prayerhome.quran

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.prayerhome.quran.audio.LeaveGuard
import com.prayerhome.quran.ui.Popup
import com.prayerhome.quran.ui.ReaderModel
import com.prayerhome.quran.ui.ReaderScreen
import com.prayerhome.quran.ui.THEMES
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = App.of(this)
    private var model by mutableStateOf<ReaderModel?>(null)
    /** The open "keep playing?" prompt came from Home (stay in the background) rather than Back (finish). */
    private var askedByHome = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Root() }
    }

    @Composable
    private fun Root() {
        // The designs are drawn on a 1920×1080 canvas: make 1dp == 1 design pixel on any screen.
        val widthPx = resources.displayMetrics.widthPixels.toFloat()
        val heightPx = resources.displayMetrics.heightPixels.toFloat()
        val scale = minOf(widthPx / 1920f, heightPx / 1080f)
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            val q = app.quran.await()
            val m = ReaderModel(app, q, scope, exit = { finish() })
            model = m
            handleExtras(intent)
            launch {
                app.recitation.state.map { Triple(it.active, it.surah to it.ayah, it.basmala) }.distinctUntilChanged().collect { (a, sa, b) ->
                    m.onRecitation(a, sa.first, sa.second, b)
                }
            }
        }
        CompositionLocalProvider(LocalDensity provides Density(scale, 1f)) {
            val m = model
            Box(Modifier.fillMaxSize().background(m?.theme?.bg ?: THEMES[app.prefs.theme.coerceIn(0, THEMES.size - 1)].bg), contentAlignment = Alignment.Center) {
                Box(Modifier.requiredSize(1920.dp, 1080.dp)) {
                    if (m != null) {
                        ReaderScreen(m)
                        LaunchedEffect(m) { m.focusText() }
                    }
                }
            }
        }
    }

    /** adb helpers: `am start -n com.prayerhome.quran/.MainActivity --ei theme 2 --ei font 1 --es goto 18:1`. */
    private fun handleExtras(intent: Intent) {
        val m = model ?: return
        if (intent.hasExtra("theme")) m.setTheme(intent.getIntExtra("theme", 0).coerceIn(0, THEMES.size - 1))
        if (intent.hasExtra("font")) m.setFont(intent.getIntExtra("font", 0).coerceIn(0, 3))
        intent.getStringExtra("goto")?.split(':')?.let { p ->
            val s = p[0].toIntOrNull() ?: return@let
            val a = p.getOrNull(1)?.toIntOrNull() ?: 1
            m.open(com.prayerhome.quran.data.Quran.indexOf(s.coerceIn(1, 114), a.coerceAtLeast(1)))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExtras(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val m = model ?: return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) { askedByHome = false; m.back() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) m.onMediaKey(event.keyCode)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        LeaveGuard.onResume()
        LeaveGuard.askFromHome = {
            val m = model
            if (m != null && m.popup != Popup.EXIT) { askedByHome = true; m.openPopup(Popup.EXIT) }
        }
        LeaveGuard.goHome = {
            runCatching { startActivity(LeaveGuard.homeIntent()) }
            if (!askedByHome) finish()
        }
    }

    override fun onPause() {
        LeaveGuard.onPause()
        super.onPause()
    }

    override fun onStop() {
        model?.onLeave()
        LeaveGuard.onStop(this)
        super.onStop()
    }

    override fun onDestroy() {
        LeaveGuard.askFromHome = null
        LeaveGuard.goHome = null
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        model?.savePosition()
    }
}
