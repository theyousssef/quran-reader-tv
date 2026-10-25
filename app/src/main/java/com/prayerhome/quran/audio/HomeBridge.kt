package com.prayerhome.quran.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.prayerhome.quran.App

/**
 * The contract with the Prayer Home launcher (com.prayerhome.tv):
 *
 * - Prayer Home broadcasts HOME_SHOWN from its onStart and HOME_HIDDEN from its onStop.
 * - When our activity stops while reciting (and not paused) we wait up to [HOME_WAIT_MS] for HOME_SHOWN.
 *   If it comes, the user went Home: keep playing and ask Prayer Home to show "Keep playing / Pause"
 *   (ASK_KEEP_PLAYING); it answers with PAUSE or KEEP_PLAYING. If it doesn't come, the user opened
 *   something else: pause.
 * - While playing in the background, HOME_HIDDEN means the user left Home for another app: pause, unless
 *   it is our own activity coming back.
 * - Without Prayer Home as the Home app, the reader asks in-app on Back, and pauses on Home.
 */
object HomeBridge {
    const val HOME_SHOWN = "com.prayerhome.quran.action.HOME_SHOWN"
    const val HOME_HIDDEN = "com.prayerhome.quran.action.HOME_HIDDEN"
    const val PAUSE = "com.prayerhome.quran.action.PAUSE"
    const val KEEP_PLAYING = "com.prayerhome.quran.action.KEEP_PLAYING"
    private const val ASK = "com.prayerhome.tv.action.ASK_KEEP_PLAYING"
    private const val LAUNCHER = "com.prayerhome.tv"
    private const val HOME_WAIT_MS = 2500L
    private const val TAG = "QuranReader"

    private val main = Handler(Looper.getMainLooper())
    var activityStarted = false
        private set
    private var lastHomeShown = 0L
    private var lastHomeHidden = 0L
    private var waitingForHome = false
    /** The user already chose "keep playing" in our own prompt (fallback without Prayer Home). */
    var keepPlayingOnExit = false

    fun prayerHomeIsHome(ctx: Context): Boolean {
        val home = ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )
        return home?.activityInfo?.packageName == LAUNCHER
    }

    fun onActivityStart() {
        activityStarted = true
        waitingForHome = false
        keepPlayingOnExit = false
        main.removeCallbacks(timeout)
    }

    fun onActivityStop(ctx: Context) {
        activityStarted = false
        val rec = App.of(ctx).recitation
        val s = rec.state.value
        if (!s.active || !s.playing) return
        if (!prayerHomeIsHome(ctx)) {
            if (keepPlayingOnExit) Log.i(TAG, "left while playing; user chose keep playing")
            else { Log.i(TAG, "left while playing without Prayer Home as Home: pause"); rec.pause() }
            keepPlayingOnExit = false
            return
        }
        Log.i(TAG, "left while playing; waiting for HOME_SHOWN")
        waitingForHome = true
        // Prayer Home's onStart can run before our onStop: accept a HOME_SHOWN that has just arrived.
        if (lastHomeShown > lastHomeHidden && SystemClock.elapsedRealtime() - lastHomeShown < HOME_WAIT_MS) { wentHome(ctx); return }
        main.removeCallbacks(timeout)
        timeoutCtx = ctx.applicationContext
        main.postDelayed(timeout, HOME_WAIT_MS)
    }

    private var timeoutCtx: Context? = null
    private val timeout = Runnable {
        if (!waitingForHome) return@Runnable
        waitingForHome = false
        Log.i(TAG, "no HOME_SHOWN within ${HOME_WAIT_MS}ms: another app opened, pause")
        timeoutCtx?.let { App.of(it).recitation.pause() }
    }

    private fun wentHome(ctx: Context) {
        waitingForHome = false
        main.removeCallbacks(timeout)
        val s = App.of(ctx).recitation.state.value
        val title = PlaybackService.title(s)
        Log.i(TAG, "went Home while playing: keep playing, ask Prayer Home ($title)")
        ctx.sendBroadcast(Intent(ASK).setPackage(LAUNCHER).putExtra("title", title))
    }

    fun onBroadcast(ctx: Context, action: String?) {
        val rec = App.of(ctx).recitation
        when (action) {
            HOME_SHOWN -> {
                lastHomeShown = SystemClock.elapsedRealtime()
                if (waitingForHome) wentHome(ctx)
            }
            HOME_HIDDEN -> {
                lastHomeHidden = SystemClock.elapsedRealtime()
                // Give our own activity a moment to come to the front (e.g. reopened from Prayer Home's Quran button).
                main.postDelayed({
                    val s = rec.state.value
                    if (!activityStarted && s.active && s.playing) {
                        Log.i(TAG, "HOME_HIDDEN while playing in the background: pause")
                        rec.pause()
                    }
                }, 400)
            }
            PAUSE -> { Log.i(TAG, "PAUSE from Prayer Home"); rec.pause() }
            KEEP_PLAYING -> Log.i(TAG, "KEEP_PLAYING from Prayer Home")
        }
    }
}

class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = HomeBridge.onBroadcast(context, intent.action)
}
