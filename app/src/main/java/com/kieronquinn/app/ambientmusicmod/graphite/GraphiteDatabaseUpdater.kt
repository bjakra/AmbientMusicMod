package com.kieronquinn.app.ambientmusicmod.graphite

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import com.kieronquinn.app.ambientmusicmod.R
import com.kieronquinn.app.ambientmusicmod.components.notifications.NotificationChannel
import com.kieronquinn.app.ambientmusicmod.components.notifications.NotificationId
import com.kieronquinn.app.ambientmusicmod.repositories.DeviceConfigRepository
import com.kieronquinn.app.ambientmusicmod.repositories.JobsRepository
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsRepository
import com.kieronquinn.app.ambientmusicmod.repositories.ShizukuServiceRepository
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import android.app.NotificationChannel as AndroidNotificationChannel

/**
 *  Graphite: music database updates. In AMM this app had network itself; here only the downloader
 *  (org.graphite.musicdb) can reach the network, and only while an update runs:
 *
 *  1. open the window: the downloader gets its network permission and the end time of the window
 *     (Settings.Global, checked by its request gate),
 *  2. the same steps as AMM: fetch the newest database list, and if newer, switch the engine to it
 *     (DeviceConfigRepository.indexManifest), which makes the engine download it,
 *  3. run the engine's download jobs now, forced: Android never runs a job that needs a network for an app
 *     without the network permission, which the engine doesn't have. So the Wi-Fi and charging switches are
 *     checked here instead, and while they aren't met nothing runs and the downloader refuses new requests.
 *     Wait until the engine reports the songs as installed or the window ends,
 *  4. close the window: network permission revoked, window cleared.
 */
object GraphiteDatabaseUpdater : KoinComponent {

    private const val TAG = "GraphiteDbUpdate"
    private const val MUSICDB = "org.graphite.musicdb"
    private const val INTERNET = "android.permission.INTERNET"
    private const val MUSICDB_MANIFEST_AUTHORITY = "org.graphite.musicdb.manifest"
    const val SETTING_UPDATE_UNTIL = "graphite_nowplaying_update_until"

    private const val MANUAL_WINDOW = 60 * 60 * 1000L
    private const val SCHEDULED_WINDOW = 6 * 60 * 60 * 1000L
    private const val PROGRESS_POLL = 3 * 1000L
    private const val EXPEDITE_EVERY_POLLS = 2
    private const val RESTART_ENGINE_AFTER_POLLS = 4

    private val context by inject<Context>()
    private val shards by inject<ShardsRepository>()
    private val deviceConfig by inject<DeviceConfigRepository>()
    private val jobs by inject<JobsRepository>()
    private val shizuku by inject<ShizukuServiceRepository>()

    private val scope = MainScope()
    private val lock = Mutex()
    val running = MutableStateFlow(false)

    /** "Update now" on the Now Playing page */
    fun updateNow() = scope.launch { run(MANUAL_WINDOW) }

    /** The automatic update schedule */
    fun scheduledUpdate() = scope.launch { run(SCHEDULED_WINDOW) }

    /**
     * True when the songs of this database version are on the phone. A new install, or an update that
     * ended before the downloads finished, leaves the engine with only its small built-in song set.
     */
    suspend fun isInstalled(versionCode: Int) = shards.getInstalledVersion() == versionCode

    /** Makes sure the downloader has no network when no update runs (called at start) */
    fun closeWindow() {
        setDownloaderNetwork(false)
        Settings.Global.putLong(context.contentResolver, SETTING_UPDATE_UNTIL, 0L)
    }

    private suspend fun run(window: Long) {
        // An update that's already running does the same thing, a second one would only wait for it
        if (lock.isLocked) return
        lock.withLock { runLocked(window) }
    }

    private suspend fun runLocked(window: Long) {
        running.value = true
        notifyApi()
        showProgress(0L, 0L, null)
        var keepAlive: android.content.ContentProviderClient? = null
        try {
            val until = System.currentTimeMillis() + window
            Settings.Global.putLong(context.contentResolver, SETTING_UPDATE_UNTIL, until)
            setDownloaderNetwork(true)
            // Keeps the downloader at this service's priority for the whole window; otherwise Android caches
            // and freezes it between the engine's requests, and downloads stall (seen on the phone). Only
            // after the grant, which restarts the downloader (new gids), and unstable, so that a downloader
            // restart never takes this process down with it.
            keepAlive = runCatching {
                context.contentResolver.acquireUnstableContentProviderClient(MUSICDB_MANIFEST_AUTHORITY)
            }.getOrNull()
            val target = shards.getUpdateTarget()
            if (target != null) {
                deviceConfig.indexManifest.set(target.url)
            }
            val version = target?.versionCode ?: shards.getLocalVersion()
            withTimeoutOrNull(window) {
                // The engine adds a download job per step (database list, then the songs), so the update
                // has to expedite again until everything is on the phone
                var polls = 0
                var nothingQueued = 0
                var restarted = false
                var total = 0L
                while (shards.getInstalledVersion() != version) {
                    val waiting = waitingFor()
                    // Also stops new requests when Wi-Fi goes away during a download
                    Settings.Global.putLong(
                        context.contentResolver, SETTING_UPDATE_UNTIL, if (waiting == null) until else 0L
                    )
                    val queued = shards.getCurrentDownloads().first()
                    val remaining = shards.getPendingDownloadBytes()
                    if (waiting == null) {
                        if (polls++ % EXPEDITE_EVERY_POLLS == 0) {
                            // Right after switching on, or after changing countries, the engine may have nothing
                            // queued. A change of its country makes it look at what it needs again.
                            if (queued == 0) deviceConfig.deviceCountry.notifyChange()
                            jobs.forceExpediteJobs()
                        }
                        // The engine only reads its countries reliably when it starts (seen after adding one)
                        nothingQueued = if (queued == 0 && remaining == 0L) nothingQueued + 1 else 0
                        if (nothingQueued >= RESTART_ENGINE_AFTER_POLLS && !restarted) {
                            restarted = true
                            shizuku.runWithService { it.forceStopNowPlaying() }
                            polls = 0
                        }
                    }
                    total = maxOf(total, remaining)
                    showProgress(total, remaining, waiting)
                    delay(PROGRESS_POLL)
                }
            } ?: Log.w(TAG, "Update window ended before the downloads finished")
        } catch (e: Exception) {
            Log.e(TAG, "Database update failed", e)
        } finally {
            notificationManager().cancel(NotificationId.DATABASE_DOWNLOAD.ordinal)
            // Let the last download job hand over its files before the network goes away
            delay(5_000)
            closeWindow()
            keepAlive?.close()
            running.value = false
            notifyApi()
        }
    }

    private fun notificationManager() = context.getSystemService(NotificationManager::class.java)

    /** The text to show when the Wi-Fi only or charging only switch holds the download back */
    private suspend fun waitingFor(): Int? {
        if (deviceConfig.superpacksRequireWiFi.get()) {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            if (network != null && !network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                return R.string.notification_database_download_waiting_wifi
            }
        }
        // Plugged in rather than BatteryManager.isCharging, which is false on a full battery
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (deviceConfig.superpacksRequireCharging.get()
            && battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0) {
            return R.string.notification_database_download_waiting_charging
        }
        return null
    }

    /** A Live Update: the progress is on the lock screen and in a status bar chip while songs download */
    private fun showProgress(total: Long, remaining: Long, waiting: Int?) {
        val channel = NotificationChannel.DATABASE_DOWNLOAD
        notificationManager().createNotificationChannel(
            AndroidNotificationChannel(channel.id, context.getString(channel.titleRes), channel.importance).apply {
                description = context.getString(channel.descRes)
                setSound(null, null)
            }
        )
        val percent = if (total > 0) ((total - remaining) * 100 / total).toInt() else 0
        val builder = Notification.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_database_download_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val downloaded = total - remaining
        if (waiting != null) {
            builder.setContentText(context.getString(waiting))
        } else if (remaining > 0) {
            builder.setContentText(context.getString(
                R.string.notification_database_download_progress,
                if (downloaded > 0) Formatter.formatShortFileSize(context, downloaded) else "0",
                Formatter.formatShortFileSize(context, total)
            ))
        } else {
            // Nothing left to download: either the engine hasn't queued anything yet, or it's installing the songs
            builder.setContentText(context.getString(
                if (total > 0) R.string.notification_database_download_installing
                else R.string.notification_database_download_preparing
            ))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // API level 36.1, which this app's build tools can't compile against
            runCatching {
                builder.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                    .invoke(builder, true)
            }
            if (remaining > 0) builder.setShortCriticalText("$percent%")
            builder.setStyle(
                Notification.ProgressStyle().setProgress(percent).setProgressIndeterminate(remaining == 0L)
            )
        } else {
            builder.setProgress(100, percent, remaining == 0L)
        }
        notificationManager().notify(NotificationId.DATABASE_DOWNLOAD.ordinal, builder.build())
    }

    private fun setDownloaderNetwork(allowed: Boolean) {
        runCatching {
            val pm = context.packageManager
            val method = if (allowed) "grantRuntimePermission" else "revokeRuntimePermission"
            pm.javaClass.getMethod(method, String::class.java, String::class.java, UserHandle::class.java)
                .invoke(pm, MUSICDB, INTERNET, Process.myUserHandle())
        }.onFailure { Log.e(TAG, "Couldn't change the downloader's network permission", it) }
    }

    private fun notifyApi() {
        context.contentResolver.notifyChange(GraphiteApiProvider.URI_STATE, null)
    }
}
