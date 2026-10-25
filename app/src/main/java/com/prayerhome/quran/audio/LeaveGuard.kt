package com.prayerhome.quran.audio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What happens to the recitation when the reader is left. The app needs nothing from the launcher:
 *
 * - Back or Home while reciting (not paused) asks "Keep playing / Pause" in the reader. Back is ours
 *   anyway; Home is caught by [HomeKeyService] (an accessibility service) while — and only while — the
 *   reader is in front and reciting.
 * - Keep playing goes to the home screen with the audio on. [HomeKeyService] then watches which app is in
 *   front and pauses as soon as it is anything but the home screen (or the reader again).
 * - Leaving any other way while reciting (another app opened from the remote, Home without the service…)
 *   pauses in the activity's onStop.
 */
object LeaveGuard {
    private const val TAG = "QuranReader"

    /** The reader activity is resumed (in front). */
    val resumed = MutableStateFlow(false)

    /** The user chose Keep playing and the reader is not in front: audio may go on over the home screen. */
    private val _keepPlayingOnHome = MutableStateFlow(false)
    val keepPlayingOnHome: StateFlow<Boolean> = _keepPlayingOnHome

    /** Set by the activity: show the prompt (Home was pressed). */
    var askFromHome: (() -> Unit)? = null

    /** Set by the activity: leave for the home screen after the prompt. */
    var goHome: (() -> Unit)? = null

    /** The accessibility service is connected (so Home can be caught and the front app watched). */
    @Volatile var serviceConnected = false

    /** The "Home won't ask" hint was shown this session (once per process). */
    var serviceHintShown = false

    fun reciting(ctx: Context): Boolean {
        val s = com.prayerhome.quran.App.of(ctx).recitation.state.value
        return s.active && s.playing
    }

    /** The prompt's answer. */
    fun leave(ctx: Context, keepPlaying: Boolean) {
        if (keepPlaying) { Log.i(TAG, "keep playing on the home screen"); _keepPlayingOnHome.value = true }
        else com.prayerhome.quran.App.of(ctx).recitation.pause()
        goHome?.invoke()
    }

    fun onResume() { resumed.value = true; _keepPlayingOnHome.value = false }

    fun onPause() { resumed.value = false }

    /** The activity is no longer visible: unless the user chose Keep playing, pause. */
    fun onStop(ctx: Context) {
        if (!reciting(ctx) || _keepPlayingOnHome.value) return
        Log.i(TAG, "left the reader while reciting: pause")
        com.prayerhome.quran.App.of(ctx).recitation.pause()
    }

    /** Something other than the home screen came to the front after Keep playing. */
    fun onOtherAppInFront(ctx: Context, pkg: String) {
        if (!_keepPlayingOnHome.value || resumed.value) return
        _keepPlayingOnHome.value = false
        if (!reciting(ctx)) return
        Log.i(TAG, "$pkg is in front, not the home screen: pause")
        com.prayerhome.quran.App.of(ctx).recitation.pause()
    }

    fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun homePackage(ctx: Context): String? =
        ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
}
