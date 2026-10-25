package com.prayerhome.quran

import android.app.Application
import android.content.Context
import com.prayerhome.quran.audio.PlaybackService
import com.prayerhome.quran.audio.Recitation
import com.prayerhome.quran.data.Prefs
import com.prayerhome.quran.data.Quran
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var prefs: Prefs
        private set
    lateinit var recitation: Recitation
        private set
    /** The Quran text is parsed once, off the main thread, as the process starts. */
    lateinit var quran: Deferred<Quran>
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        quran = scope.async(Dispatchers.Default) { Quran.load(this@App) }
        recitation = Recitation(this, prefs).also { r -> r.onActivated = { PlaybackService.start(this) } }
    }

    companion object {
        fun of(ctx: Context) = ctx.applicationContext as App
    }
}
