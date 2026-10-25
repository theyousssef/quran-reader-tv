package com.prayerhome.quran.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.prayerhome.quran.data.Prefs
import com.prayerhome.quran.data.Quran
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Verse-by-verse recitation with gapless hand-over.
 *
 * - A window of at most [MAX_FILES] verses (the one playing plus the next ones) is downloaded, in order,
 *   into cacheDir/recite. Each verse's file is deleted the moment it finishes playing; Stop deletes all.
 * - The verse after the current one is prepared in a second [MediaPlayer] and chained with
 *   [MediaPlayer.setNextMediaPlayer], so the platform starts it on the same audio clock as the current
 *   one completes — no gap, no extra library.
 * - Before verse 1 of every surah except Al-Fatiha (whose verse 1 is the basmala) and At-Tawbah (none),
 *   the reciter's 001001 is played as the basmala.
 *
 * All player calls happen on the main thread.
 */
class Recitation(private val ctx: Context, private val prefs: Prefs) {

    /** One queued audio clip. [basmala] items play 001001 before verse 1 of [surah]. */
    data class Item(val seq: Long, val surah: Int, val ayah: Int, val basmala: Boolean) {
        val fileSurah get() = if (basmala) 1 else surah
        val fileAyah get() = if (basmala) 1 else ayah
    }

    data class State(
        /** The player bar is shown (between Recite and Stop). */
        val active: Boolean = false,
        /** The user wants it playing (false = paused). */
        val playing: Boolean = false,
        /** Waiting for the current verse's audio. */
        val buffering: Boolean = false,
        val surah: Int = 1,
        val ayah: Int = 1,
        /** The basmala before [surah] is being recited. */
        val basmala: Boolean = false,
        val reciter: Reciter = Reciters.all[0],
        val repeat: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State(reciter = Reciters.byFolder(prefs.reciter), repeat = prefs.repeat))
    val state: StateFlow<State> = _state
    private fun update(f: State.() -> State) { _state.value = _state.value.f() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dir = File(ctx.cacheDir, "recite")
    private val audio = ctx.getSystemService(AudioManager::class.java)

    private val window = ArrayDeque<Item>()
    private val ready = HashMap<Long, File>()
    private var seq = 0L
    private var downloader: Job? = null

    private var cur: MediaPlayer? = null
    private var curItem: Item? = null
    private var curPrepared = false
    private var nxt: MediaPlayer? = null
    private var nxtItem: Item? = null
    private var nxtPrepared = false
    private var errors = 0
    private var lastCompletionAt = 0L

    /** Called whenever playback starts after being stopped/inactive (the service is started then). */
    var onActivated: (() -> Unit)? = null

    // ------------------------------------------------------------------ public controls

    /** Start reciting at [surah]:[ayah] (a fresh session: leftover audio from earlier runs is deleted first). */
    fun start(surah: Int, ayah: Int) {
        val wasActive = _state.value.active
        releasePlayers()
        downloader?.cancel()
        window.clear(); ready.clear()
        val leftovers = fileCount()
        purgeDir()
        if (leftovers > 0) Log.i(TAG, "deleted $leftovers leftover audio files before starting")
        val first = if (ayah == 1 && Quran.hasBasmalaHeader(surah)) Item(++seq, surah, 1, true) else Item(++seq, surah, ayah, false)
        window.addLast(first)
        fillWindow()
        errors = 0
        update { copy(active = true, playing = true, buffering = true, surah = first.surah, ayah = first.ayah, basmala = first.basmala, error = null) }
        requestFocus()
        startDownloader()
        if (!wasActive) onActivated?.invoke()
        Log.i(TAG, "start ${first.surah}:${first.ayah} basmala=${first.basmala} reciter=${_state.value.reciter.folder}")
    }

    fun pause() {
        if (!_state.value.active || !_state.value.playing) return
        if (curPrepared) cur?.pause()
        update { copy(playing = false) }
        Log.i(TAG, "pause")
    }

    fun play() {
        val s = _state.value
        if (!s.active) return
        if (s.playing) return
        requestFocus()
        update { copy(playing = true) }
        if (curPrepared) cur?.start()
        Log.i(TAG, "play")
    }

    fun togglePause() = if (_state.value.playing) pause() else play()

    /** Stop: hide the player and delete every prefetched file. */
    fun stop() {
        if (!_state.value.active && cur == null) { purgeDir(); return }
        releasePlayers()
        downloader?.cancel(); downloader = null
        window.clear(); ready.clear()
        purgeDir()
        abandonFocus()
        update { copy(active = false, playing = false, buffering = false) }
        Log.i(TAG, "stop; files on disk: ${fileCount()}")
    }

    /** Move the recitation to [surah]:[ayah] (its basmala first for verse 1) and keep reciting from there. */
    fun seek(surah: Int, ayah: Int) {
        if (!_state.value.active) { start(surah, ayah); return }
        jump(surah, ayah, ayah == 1 && Quran.hasBasmalaHeader(surah))
        play()
    }

    fun next() {
        val s = _state.value
        if (!s.active) return
        val n = nextOf(Item(0, s.surah, s.ayah, s.basmala)) ?: return
        jump(n.surah, n.ayah, n.basmala)
    }

    fun previous() {
        val s = _state.value
        if (!s.active) return
        when {
            s.basmala -> if (s.surah > 1) jump(s.surah - 1, Quran.ayahs(s.surah - 1), false)
            s.ayah > 1 -> jump(s.surah, s.ayah - 1, false)
            Quran.hasBasmalaHeader(s.surah) -> jump(s.surah, 1, true)
            s.surah > 1 -> jump(s.surah - 1, Quran.ayahs(s.surah - 1), false)
        }
    }

    fun toggleRepeat() {
        val r = !_state.value.repeat
        prefs.repeat = r
        update { copy(repeat = r) }
        if (curPrepared) cur?.isLooping = r
    }

    fun setReciter(r: Reciter) {
        prefs.reciter = r.folder
        val s = _state.value
        update { copy(reciter = r) }
        if (s.active) {
            val playing = s.playing
            jump(s.surah, s.ayah, s.basmala)
            if (!playing) pause()
        }
    }

    fun positionMs(): Int = if (curPrepared) runCatching { cur!!.currentPosition }.getOrDefault(0) else 0

    /** A reading of the playing clip: which verse ([surah], [ayah], [basmala]), where it was and when (System.nanoTime). */
    class Sample(val surah: Int, val ayah: Int, val basmala: Boolean, val posMs: Int, val durMs: Int, val atNanos: Long)

    /** The latest [requestSample] result, or null. */
    @Volatile var sample: Sample? = null
        private set
    private val sampler = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val sampling = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Reads the clip's position and duration off the UI thread. These are calls into the media server, which
     * blocks them while it chains the next clip (up to ~250 ms on this TV); read on the UI thread, that froze
     * the page at every verse hand-over. The result lands in [sample]; one reading at a time.
     */
    fun requestSample() {
        val mp = cur?.takeIf { curPrepared } ?: return
        val i = curItem ?: return
        if (!sampling.compareAndSet(false, true)) return
        sampler.execute {
            try {
                val pos = mp.currentPosition
                val at = System.nanoTime()
                sample = Sample(i.surah, i.ayah, i.basmala, pos, mp.duration, at)
            } catch (_: Exception) {
            } finally { sampling.set(false) }
        }
    }

    // ------------------------------------------------------------------ queue

    private fun nextOf(i: Item): Item? = when {
        i.basmala -> Item(++seq, i.surah, 1, false)
        i.ayah < Quran.ayahs(i.surah) -> Item(++seq, i.surah, i.ayah + 1, false)
        i.surah < 114 -> if (Quran.hasBasmalaHeader(i.surah + 1)) Item(++seq, i.surah + 1, 1, true) else Item(++seq, i.surah + 1, 1, false)
        else -> null
    }

    private fun fillWindow() {
        while (window.size < MAX_FILES) window.addLast(nextOf(window.lastOrNull() ?: return) ?: return)
    }

    private fun jump(surah: Int, ayah: Int, basmala: Boolean) {
        val playing = _state.value.playing
        releasePlayers()
        downloader?.cancel()
        window.clear(); ready.clear()
        purgeDir()
        window.addLast(Item(++seq, surah, ayah, basmala))
        fillWindow()
        errors = 0
        update { copy(surah = surah, ayah = ayah, basmala = basmala, buffering = true, playing = playing) }
        startDownloader()
        Log.i(TAG, "jump $surah:$ayah basmala=$basmala")
    }

    private fun fileFor(i: Item) = File(dir, "${i.seq}_%03d%03d.mp3".format(i.fileSurah, i.fileAyah))

    private fun startDownloader() {
        downloader?.cancel()
        val reciter = _state.value.reciter
        downloader = scope.launch {
            var attempt = 0
            while (isActive) {
                val item = window.firstOrNull { it.seq !in ready } ?: run { waitForWork(); null } ?: continue
                val f = fileFor(item)
                val ok = try {
                    withContext(Dispatchers.IO) { download(Reciters.url(reciter, item.fileSurah, item.fileAyah), f) }
                } finally {
                    // Cancelled (Stop / jump) while downloading: don't leave the file behind.
                    if (!isActive) { f.delete(); File(f.path + ".part").delete() }
                }
                if (ok && window.any { it.seq == item.seq }) {
                    attempt = 0
                    ready[item.seq] = f
                    Log.d(TAG, "ready ${item.surah}:${item.ayah}${if (item.basmala) " (basmala)" else ""}; files on disk: ${fileCount()}")
                    tryPrepare()
                } else if (!ok) {
                    f.delete()
                    attempt++
                    if (window.firstOrNull()?.seq == item.seq) update { copy(error = "Can't reach everyayah.com — retrying") }
                    delay(minOf(10_000L, 1500L * attempt))
                } else f.delete()
            }
        }
    }

    private var wake: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private suspend fun waitForWork() {
        val d = kotlinx.coroutines.CompletableDeferred<Unit>()
        wake = d
        d.await()
    }
    private fun signalWork() { wake?.complete(Unit); wake = null }

    private fun download(url: String, f: File): Boolean = try {
        dir.mkdirs()
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 15000
            setRequestProperty("User-Agent", "QuranReader/1.0 (Android TV)")
        }
        try {
            if (c.responseCode != 200) false else {
                val tmp = File(f.path + ".part")
                c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it, 32 * 1024) } }
                tmp.renameTo(f)
            }
        } finally { c.disconnect() }
    } catch (e: Exception) {
        Log.w(TAG, "download failed $url: ${e.message}")
        false
    }

    private fun purgeDir() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun fileCount(): Int = dir.listFiles()?.size ?: 0

    // ------------------------------------------------------------------ players

    /**
     * Slow player calls run here, in order, off the UI thread: on this TV setNextMediaPlayer can block for
     * ~250 ms, creating a player ~20 ms and release ~10 ms, which used to stall the reader's frames at every
     * verse hand-over. A player created there has no looper, so its callbacks still come on the main thread.
     */
    private val playerOps = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** The next verse's player is being created on [playerOps]. */
    private var nxtPending = false
    /** Bumped whenever the players are released, so a player created for an older queue is dropped. */
    private var playersGen = 0

    private fun newPlayer(i: Item, f: File, prepare: Boolean = true, onPrepared: (MediaPlayer) -> Unit): MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(ATTRS)
        setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
        setDataSource(f.path)
        setOnPreparedListener { onPrepared(it) }
        setOnCompletionListener { onCompletion(it) }
        setOnErrorListener { mp, what, extra -> onError(mp, i, what, extra); true }
        setOnInfoListener { mp, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_STARTED_AS_NEXT) {
                // Both callbacks are posted from the native player at the hand-over; the position shows how
                // much of this verse had already played, i.e. when it really started.
                val pos = runCatching { mp.currentPosition }.getOrDefault(-1)
                val since = SystemClock.elapsedRealtime() - lastCompletionAt
                Log.i(TAG, "gapless: ${i.surah}:${i.ayah} started as next; callback ${since} ms after the previous completion callback, already played ${pos} ms")
            }
            false
        }
        if (prepare) prepareAsync()
    }

    private fun tryPrepare() {
        val first = window.firstOrNull() ?: return
        if (cur == null) {
            val f = ready[first.seq] ?: return
            curItem = first; curPrepared = false
            cur = newPlayer(first, f) { mp -> onPrepared(mp) }
            return
        }
        if (!curPrepared || nxt != null || nxtPending) return
        val second = window.getOrNull(1) ?: return
        val f = ready[second.seq] ?: return
        nxtItem = second; nxtPrepared = false; nxtPending = true
        val gen = playersGen
        playerOps.execute {
            val mp = runCatching { newPlayer(second, f, prepare = false) { mp -> onPrepared(mp) } }.getOrNull()
            main.post {
                if (gen != playersGen) { mp?.let { playerOps.execute { it.release() } }; return@post }
                nxtPending = false
                if (mp == null || nxtItem !== second || cur == null) {
                    mp?.let { playerOps.execute { it.release() } }
                    if (nxtItem === second) nxtItem = null
                    tryPrepare()
                    return@post
                }
                nxt = mp
                runCatching { mp.prepareAsync() }.onFailure { Log.w(TAG, "prepareAsync failed", it) }
            }
        }
    }

    private fun onPrepared(mp: MediaPlayer) {
        when {
            mp === cur -> {
                // The verse to play now (first one, after a jump, or a next one promoted before it was ready).
                curPrepared = true
                mp.isLooping = _state.value.repeat
                val i = curItem!!
                update { copy(buffering = false, error = null, surah = i.surah, ayah = i.ayah, basmala = i.basmala) }
                if (_state.value.playing) mp.start()
                tryPrepare()
            }
            mp === nxt -> {
                nxtPrepared = true
                val c = cur
                playerOps.execute { runCatching { c?.setNextMediaPlayer(mp) }.onFailure { Log.w(TAG, "setNextMediaPlayer failed", it) } }
            }
            else -> mp.release()
        }
    }

    private fun onCompletion(mp: MediaPlayer) {
        if (mp !== cur) return
        lastCompletionAt = SystemClock.elapsedRealtime()
        val done = curItem
        val next = nxt
        val nextWasChained = next != null && nxtPrepared
        val nextPlaying = nextWasChained && runCatching { next!!.isPlaying }.getOrDefault(false)
        val nextPos = if (nextPlaying) runCatching { next!!.currentPosition }.getOrDefault(-1) else -1
        val doneFiles = if (done != null) listOfNotNull(ready.remove(done.seq), fileFor(done)) else emptyList()
        playerOps.execute { runCatching { mp.release() }; doneFiles.forEach { it.delete() } }
        if (window.firstOrNull()?.seq == done?.seq) window.removeFirst()
        cur = next; curItem = nxtItem; curPrepared = nxtPrepared
        nxt = null; nxtItem = null; nxtPrepared = false
        Log.i(TAG, "completed ${done?.surah}:${done?.ayah}; next chained=$nextWasChained playing=$nextPlaying pos=${nextPos}ms; files on disk: ${fileCount()}")
        val now = window.firstOrNull()
        if (now == null) { stop(); return }
        if (cur != null && curPrepared) {
            cur!!.isLooping = _state.value.repeat
            if (_state.value.playing && !nextPlaying) cur!!.start()
            update { copy(surah = now.surah, ayah = now.ayah, basmala = now.basmala, buffering = false) }
        } else {
            update { copy(surah = now.surah, ayah = now.ayah, basmala = now.basmala, buffering = true) }
        }
        fillWindow()
        signalWork()
        tryPrepare()
    }

    private fun onError(mp: MediaPlayer, i: Item, what: Int, extra: Int) {
        Log.w(TAG, "player error $what/$extra on ${i.surah}:${i.ayah}")
        if (mp === nxt) {
            runCatching { cur?.setNextMediaPlayer(null) }
            mp.release(); nxt = null; nxtItem = null; nxtPrepared = false
            ready.remove(i.seq)?.delete(); signalWork(); return
        }
        if (mp !== cur) { mp.release(); return }
        // Corrupt or truncated file: fetch it again, and skip the verse if it keeps failing.
        releasePlayers()
        ready.remove(i.seq)?.delete()
        if (++errors >= 3) { errors = 0; window.removeFirst(); fillWindow() }
        if (window.isEmpty()) { stop(); return }
        startDownloader()
    }

    private fun releasePlayers() {
        cur?.let { runCatching { it.release() } }
        nxt?.let { runCatching { it.release() } }
        cur = null; nxt = null; curItem = null; nxtItem = null; curPrepared = false; nxtPrepared = false
        nxtPending = false; playersGen++
    }

    // ------------------------------------------------------------------ audio focus

    private var resumeOnGain = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(ATTRS)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnGain = _state.value.playing; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> cur?.setVolume(0.3f, 0.3f)
                AudioManager.AUDIOFOCUS_GAIN -> {
                    cur?.setVolume(1f, 1f)
                    if (resumeOnGain) { resumeOnGain = false; play() }
                }
            }
        }
        .build()

    private fun requestFocus() { audio.requestAudioFocus(focusRequest) }
    private fun abandonFocus() { audio.abandonAudioFocusRequest(focusRequest) }

    companion object {
        const val TAG = "QuranReader"
        /** Audio files kept on disk at once: the verse playing plus the ones ahead of it. */
        const val MAX_FILES = 10
        private val ATTRS = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
