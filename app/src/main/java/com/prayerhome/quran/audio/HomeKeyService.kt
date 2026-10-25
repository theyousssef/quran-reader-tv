package com.prayerhome.quran.audio

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
 *
 * Volume keys never reach [onKeyEvent], but while any service filters keys the system stops repeating
 * them, so a held volume key moves one step only. So whenever the volume changes, key filtering is
 * switched off for [VOLUME_PAUSE_MS] (restarted on every further change) and then back on: holding
 * a volume key keeps going, and Home is caught again once the volume has been left alone for a moment.
 */
class HomeKeyService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var filtering = false
    private var watching = false
    private var homeDownConsumed = false
    private var mode = Mode.IDLE
    /** The volume changed less than [VOLUME_PAUSE_MS] ago: don't filter keys. */
    private var volumePause = false
    private var volumeReceiver: BroadcastReceiver? = null

    private enum class Mode { IDLE, FILTER, WATCH }

    override fun onServiceConnected() {
        LeaveGuard.serviceConnected = true
        val rec = App.of(this).recitation
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onVolumeChanged()
        }
        val f = IntentFilter(VOLUME_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
        volumeReceiver = r
        s.launch {
            combine(LeaveGuard.resumed, LeaveGuard.keepPlayingOnHome, rec.state) { resumed, keep, st ->
                val reciting = st.active && st.playing
                when {
                    resumed && reciting -> Mode.FILTER
                    !resumed && keep && reciting -> Mode.WATCH
                    else -> Mode.IDLE
                }
            }.distinctUntilChanged().collect { mode = it; apply() }
        }
    }

    private fun onVolumeChanged() {
        main.removeCallbacks(volumeDone)
        main.postDelayed(volumeDone, VOLUME_PAUSE_MS)
        if (volumePause) return
        volumePause = true
        if (filtering) { Log.i(TAG, "volume changed: stop filtering keys for a moment"); apply() }
    }

    private val volumeDone = Runnable {
        volumePause = false
        if (mode == Mode.FILTER) { Log.i(TAG, "volume left alone: filter keys again"); apply() }
    }

    private fun apply() {
        val info = serviceInfo ?: return
        filtering = mode == Mode.FILTER && !volumePause
        watching = mode == Mode.WATCH
        var flags = info.flags and (AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS).inv()
        if (filtering) flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        if (watching) flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.flags = flags
        info.eventTypes = if (watching) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED else 0
        serviceInfo = info
        Log.i(TAG, "mode $mode${if (mode == Mode.FILTER && !filtering) " (paused: volume)" else ""}")
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
        volumeReceiver?.let { runCatching { unregisterReceiver(it) } }
        scope?.cancel()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "QuranReader"
        /** AudioManager.VOLUME_CHANGED_ACTION (hidden in the SDK, sent by the system on every volume change). */
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val VOLUME_PAUSE_MS = 2000L
    }
}
