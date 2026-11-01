package com.prayerhome.quran.update

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the app up to date from the GitHub releases of [repo] ("owner/name"). Once a day it asks
 * for the latest release; when its tag is newer than [currentVersion] it downloads the release's
 * APK and installs it over this one, without asking, as soon as none of the app's screens is
 * showing and [busy] is false. Android lets an app update itself silently; on a device that still
 * asks, the system's install prompt opens the next time the app comes to the front.
 */
class SelfUpdater(
    private val app: Application,
    private val repo: String,
    private val currentVersion: String,
    private val busy: () -> Boolean = { false },
) {
    private val prefs = app.getSharedPreferences("self_update", Context.MODE_PRIVATE)
    private val apk = File(app.filesDir, "update/update.apk")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Activities between onStart and onStop. */
    @Volatile private var started = 0
    private val installing = AtomicBoolean(false)

    fun start() {
        instance = this
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) { if (--started == 0) installWhenIdle() }
            override fun onActivityResumed(activity: Activity) {
                askForInstallPermission(activity)
                // the last silent attempt was refused: ask through the system prompt now that we're in front
                if (prefs.getBoolean(KEY_ASK, false) && !installing.get() && apk.exists()) {
                    prefs.edit().putBoolean(KEY_ASK, false).apply()
                    scope.launch { install() }
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        scope.launch {
            dropStaleApk()
            while (isActive) {
                if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) >= DAY_MS) check()
                installWhenIdle()
                delay(HOUR_MS)
            }
        }
    }

    /**
     * Without "Install unknown apps" every update stops at a confirmation, so explain that and open
     * the app's own switch in Settings. Asked again a week later if it's still off.
     */
    private fun askForInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_ASKED_PERMISSION, 0) < WEEK_MS) return
        prefs.edit().putLong(KEY_ASKED_PERMISSION, now).apply()
        val name = app.applicationInfo.loadLabel(app.packageManager)
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Turn on automatic updates")
            .setMessage(
                "$name can keep itself up to date, but Android needs your permission first. " +
                    "Please allow it: on the next screen, turn on $name, then press Back."
            )
            .setPositiveButton("Allow") { _, _ ->
                val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                runCatching { activity.startActivity(settings) }
                    .onFailure { runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) } }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun check() {
        // a failed check also waits a day
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        try {
            val release = JSONObject(read(open("https://api.github.com/repos/$repo/releases/latest")))
            val tag = release.optString("tag_name")
            if (!isNewerVersion(tag, currentVersion)) return
            if (apk.exists() && (apkVersion(apk) ?: 0) > installedVersion()) return // already downloaded
            val url = pickApkUrl(release.optJSONArray("assets") ?: JSONArray()) ?: return
            val part = File(apk.parentFile, "update.part")
            apk.parentFile?.mkdirs()
            open(url).inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            if ((apkVersion(part) ?: 0) > installedVersion()) part.renameTo(apk)
            else Log.w(TAG, "release $tag holds no newer APK of ${app.packageName}")
            part.delete()
            Log.i(TAG, "downloaded $tag")
        } catch (e: Exception) {
            Log.w(TAG, "update check failed: $e")
        }
    }

    private fun installWhenIdle() {
        if (started == 0 && !installing.get() && apk.exists() && !busy()) scope.launch { install() }
    }

    private fun install() {
        if (!installing.compareAndSet(false, true)) return
        try {
            val installer = app.packageManager.packageInstaller
            installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 31) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val done = PendingIntent.getBroadcast(app, id, Intent(app, SelfUpdateReceiver::class.java), flags)
                session.commit(done.intentSender)
            }
            Log.i(TAG, "installing ${apk.length()} bytes")
        } catch (e: Exception) {
            Log.w(TAG, "install failed: $e")
            installing.set(false)
        }
    }

    internal fun onResult(context: Context, intent: Intent) {
        installing.set(false)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> apk.delete() // usually never reached: the update ends this process
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (started > 0 && confirm != null) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    prefs.edit().putBoolean(KEY_ASK, true).apply()
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> Log.w(TAG, "install session abandoned")
            else -> {
                Log.w(TAG, "install failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                apk.delete()
            }
        }
    }

    private fun dropStaleApk() {
        if (apk.exists() && (apkVersion(apk) ?: 0) <= installedVersion()) apk.delete()
    }

    private fun installedVersion(): Long = versionOf(app.packageManager.getPackageInfo(app.packageName, 0))

    /** The version code of [file] when it's an APK of this app. */
    private fun apkVersion(file: File): Long? {
        val info = app.packageManager.getPackageArchiveInfo(file.path, 0) ?: return null
        return if (info.packageName == app.packageName) versionOf(info) else null
    }

    @Suppress("DEPRECATION")
    private fun versionOf(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", app.packageName)
            if (responseCode != 200) throw IllegalStateException("HTTP $responseCode for $url")
        }

    private fun read(conn: HttpURLConnection) = conn.inputStream.bufferedReader().use { it.readText() }

    companion object {
        private const val TAG = "SelfUpdater"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_ASK = "ask_next_time"
        private const val KEY_ASKED_PERMISSION = "asked_permission"
        private const val HOUR_MS = 60L * 60 * 1000
        private const val DAY_MS = 24 * HOUR_MS
        private const val WEEK_MS = 7 * DAY_MS

        @Volatile internal var instance: SelfUpdater? = null

        /** Compares the numbers in a release tag ("v1.6") with the app's version ("1.5.1"). */
        fun isNewerVersion(tag: String, current: String): Boolean {
            val a = Regex("\\d+").findAll(tag).map { it.value.toLong() }.toList()
            val b = Regex("\\d+").findAll(current).map { it.value.toLong() }.toList()
            if (a.isEmpty()) return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /** The release's APK for this device's CPU, or its only APK. */
        private fun pickApkUrl(assets: JSONArray): String? {
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk") }
            val forCpu = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
                apks.firstOrNull { it.optString("name").contains(abi) }
            }
            return (forCpu ?: apks.firstOrNull())?.optString("browser_download_url")?.takeIf { it.isNotEmpty() }
        }
    }
}

/** Hears how an install went. */
class SelfUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SelfUpdater.instance?.onResult(context, intent)
    }
}
