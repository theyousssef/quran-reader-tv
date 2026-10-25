package com.prayerhome.quran.audio

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.prayerhome.quran.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Catches the remote's Home key while the reader is in front and reciting, so the reader can ask
 * "Keep playing / Pause" first; after Keep playing, pauses once anything but the home screen is in front.
 *
 * The rest of the time it asks for nothing: key filtering and window events are switched on with
 * [setServiceInfo] only while needed, so the remote is untouched otherwise.
 */
class HomeKeyService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var filtering = false
    private var watching = false
    private var homeDownConsumed = false

    private enum class Mode { IDLE, FILTER, WATCH }

    override fun onServiceConnected() {
        LeaveGuard.serviceConnected = true
        val rec = App.of(this).recitation
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            combine(LeaveGuard.resumed, LeaveGuard.keepPlayingOnHome, rec.state) { resumed, keep, st ->
                val reciting = st.active && st.playing
                when {
                    resumed && reciting -> Mode.FILTER
                    !resumed && keep && reciting -> Mode.WATCH
                    else -> Mode.IDLE
                }
            }.distinctUntilChanged().collect { apply(it) }
        }
    }

    private fun apply(mode: Mode) {
        val info = serviceInfo ?: return
        filtering = mode == Mode.FILTER
        watching = mode == Mode.WATCH
        var flags = info.flags and (AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS).inv()
        if (filtering) flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        if (watching) flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.flags = flags
        info.eventTypes = if (watching) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED else 0
        serviceInfo = info
        Log.i(TAG, "mode $mode")
        if (watching) main.postDelayed(check, 500) // in case something else is already in front
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                Log.d(TAG, "filter saw ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} repeat=${event.repeatCount}")
            return false
        }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // checked again here: the flag may lag a moment behind the state
                if (!filtering || !LeaveGuard.resumed.value || !LeaveGuard.reciting(this)) return false
                homeDownConsumed = true
                if (event.repeatCount == 0) {
                    Log.i(TAG, "Home while reciting: ask first")
                    main.post { LeaveGuard.askFromHome?.invoke() }
                }
                true
            }
            KeyEvent.ACTION_UP -> homeDownConsumed.also { homeDownConsumed = false }
            else -> false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!watching || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        Log.d(TAG, "window: ${event.packageName} ${event.className}")
        main.removeCallbacks(check)
        main.postDelayed(check, 250) // let the window list settle
    }

    private val check = Runnable {
        if (!watching) return@Runnable
        val front = frontApp() ?: return@Runnable
        val ignored = setOf(packageName, LeaveGuard.homePackage(this), "com.android.systemui", "android")
        Log.d(TAG, "front app: $front")
        if (front !in ignored) LeaveGuard.onOtherAppInFront(this, front)
    }

    /**
     * The package of the focused application window. Overlays (other apps' banners), toasts and system
     * panels are not application windows, so they never count.
     */
    private fun frontApp(): String? {
        val ws = runCatching { windows }.getOrNull().orEmpty()
        val app = ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            ?: ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?: return null
        val root = app.root ?: return null
        return root.packageName?.toString()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        LeaveGuard.serviceConnected = false
        scope?.cancel()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "QuranReader"
    }
}
