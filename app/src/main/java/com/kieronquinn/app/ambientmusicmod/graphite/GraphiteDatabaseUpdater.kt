package com.kieronquinn.app.ambientmusicmod.graphite

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
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
 *  3. run the engine's download jobs now (forced for "Update now", with the user's Wi-Fi/charging
 *     conditions for scheduled updates), wait until the engine reports the songs as installed or the
 *     window ends,
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
    private const val EXPEDITE_EVERY_POLLS = 5

    private val context by inject<Context>()
    private val shards by inject<ShardsRepository>()
    private val deviceConfig by inject<DeviceConfigRepository>()
    private val jobs by inject<JobsRepository>()

    private val scope = MainScope()
    private val lock = Mutex()
    val running = MutableStateFlow(false)

    /** "Update now" on the Now Playing page */
    fun updateNow() = scope.launch { run(force = true, window = MANUAL_WINDOW) }

    /** The automatic update schedule */
    fun scheduledUpdate() = scope.launch { run(force = false, window = SCHEDULED_WINDOW) }

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

    private suspend fun run(force: Boolean, window: Long) = lock.withLock {
        running.value = true
        notifyApi()
        var keepAlive: android.content.ContentProviderClient? = null
        try {
            Settings.Global.putLong(
                context.contentResolver, SETTING_UPDATE_UNTIL, System.currentTimeMillis() + window
            )
            setDownloaderNetwork(true)
            // Keeps the downloader at this service's priority for the whole window; otherwise Android caches
            // and freezes it between the engine's requests, and downloads stall (seen on the phone). Only
            // after the grant, which restarts the downloader (new gids), and unstable, so that a downloader
            // restart never takes this process down with it.
            keepAlive = runCatching {
                context.contentResolver.acquireUnstableContentProviderClient(MUSICDB_MANIFEST_AUTHORITY)
            }.getOrNull()
            val state = shards.getShardsState(true).first()
            val target = state.remote?.takeIf { state.updateAvailable }
            if (target != null) {
                deviceConfig.indexManifest.set(target.url)
            }
            val version = target?.versionCode ?: state.local.versionCode
            if (!force) jobs.setShouldExpediteJobs(true)
            withTimeoutOrNull(window) {
                // The engine adds a download job per step (database list, then the songs), so a forced
                // update has to expedite again until everything is on the phone
                var polls = 0
                var total = 0L
                while (shards.getInstalledVersion() != version) {
                    if (polls++ % EXPEDITE_EVERY_POLLS == 0) {
                        // Right after switching on, or after changing countries, the engine may have nothing
                        // queued. A change of its country makes it look at what it needs again.
                        if (shards.getCurrentDownloads().first() == 0) deviceConfig.deviceCountry.notifyChange()
                        if (force) jobs.forceExpediteJobs()
                    }
                    val remaining = shards.getPendingDownloadBytes()
                    total = maxOf(total, remaining)
                    showProgress(total, remaining)
                    delay(PROGRESS_POLL)
                }
            } ?: Log.w(TAG, "Update window ended before the downloads finished")
        } catch (e: Exception) {
            Log.e(TAG, "Database update failed", e)
        } finally {
            if (!force) jobs.setShouldExpediteJobs(false)
            // Let the last download job hand over its files before the network goes away
            delay(5_000)
            notificationManager().cancel(NotificationId.DATABASE_DOWNLOAD.ordinal)
            closeWindow()
            keepAlive?.close()
            running.value = false
            notifyApi()
        }
    }

    private fun notificationManager() = context.getSystemService(NotificationManager::class.java)

    /** A Live Update: the progress is on the lock screen and in a status bar chip while songs download */
    private fun showProgress(total: Long, remaining: Long) {
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
        if (remaining > 0) {
            builder.setContentText(context.getString(
                R.string.notification_database_download_progress,
                Formatter.formatShortFileSize(context, remaining),
                Formatter.formatShortFileSize(context, total)
            ))
        } else {
            builder.setContentText(context.getString(R.string.notification_database_download_preparing))
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
