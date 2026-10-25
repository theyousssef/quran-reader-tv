package com.prayerhome.quran.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import com.prayerhome.quran.App
import com.prayerhome.quran.MainActivity
import com.prayerhome.quran.R
import com.prayerhome.quran.data.Quran
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps recitation alive outside the app: a mediaPlayback foreground service with a [MediaSession],
 * so the remote's play/pause, stop, next and previous keys reach us from the home screen too.
 * It runs from Recite until Stop.
 */
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession
    private val rec get() = App.of(this).recitation

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recitation", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "QuranReader").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = rec.play()
                override fun onPause() = rec.pause()
                override fun onStop() = rec.stop()
                override fun onSkipToNext() = rec.next()
                override fun onSkipToPrevious() = rec.previous()
            })
            setSessionActivity(openApp())
            isActive = true
        }
        scope.launch {
            rec.state.collect { s ->
                if (!s.active) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                publish(s)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(rec.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (!rec.state.value.active) stopSelf()
        return START_NOT_STICKY
    }

    private fun publish(s: Recitation.State) {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val st = when {
            s.playing && s.buffering -> PlaybackState.STATE_BUFFERING
            s.playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(PlaybackState.Builder().setActions(actions).setState(st, rec.positionMs().toLong(), 1f).build())
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title(s))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.reciter.en)
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
    }

    private fun notification(s: Recitation.State): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title(s))
            .setContentText(s.reciter.en)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .build()

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "recitation"
        private const val NOTIFICATION_ID = 7

        fun title(s: Recitation.State) =
            if (s.basmala) "${Quran.nameEn(s.surah)} · basmala" else "${Quran.nameEn(s.surah)} · verse ${s.ayah}"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PlaybackService::class.java))
        }
    }
}
