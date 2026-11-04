package com.kieronquinn.app.ambientmusicmod.graphite

import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
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

/**
 *  Graphite: music database updates. In AMM this app had network itself; here only the downloader
 *  (org.graphite.musicdb) can reach the network, and only while an update runs:
 *
 *  1. open the window: the downloader gets its network permission and the end time of the window
 *     (Settings.Global, checked by its request gate),
 *  2. the same steps as AMM: fetch the newest database list, and if newer, switch the engine to it
 *     (DeviceConfigRepository.indexManifest), which makes the engine download it,
 *  3. run the engine's download jobs now (forced for "Update now", with the user's Wi-Fi/charging
 *     conditions for scheduled updates), wait until its download queue is empty or the window ends,
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
    /** Time for the engine to queue its downloads after the jobs are expedited */
    private const val DOWNLOAD_QUEUE_SETTLE = 30 * 1000L

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

    private const val KEY_INSTALLED_VERSION = "installed_version"

    private fun prefs() = context.getSharedPreferences("graphite_database", Context.MODE_PRIVATE)

    /**
     * True when an update finished downloading the current database version. A new install, or an
     * update that ended before the downloads finished, leaves the engine without songs to match.
     */
    fun isInstalled(versionCode: Int) = prefs().getInt(KEY_INSTALLED_VERSION, -1) == versionCode

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
            if (force) jobs.forceExpediteJobs() else jobs.setShouldExpediteJobs(true)
            withTimeoutOrNull(window) {
                if (target != null) {
                    // The engine first has to switch to the new version
                    while (shards.getShardsState(false).first().local.versionCode != target.versionCode) {
                        delay(10_000)
                    }
                }
                // Done when the engine's download queue is empty again. It always keeps one charging+idle
                // manifest recheck queued, so "empty" means at most one entry. The version alone isn't
                // enough: when it's already current (a new install starts with the newest list), the
                // songs themselves still have to download, which only the queue shows.
                delay(DOWNLOAD_QUEUE_SETTLE)
                var idlePolls = 0
                while (idlePolls < 2) {
                    val queued = shards.getCurrentDownloads().first()
                    idlePolls = if (queued <= 1) idlePolls + 1 else 0
                    delay(10_000)
                }
                // The songs of this version are on the phone now
                prefs().edit().putInt(KEY_INSTALLED_VERSION,
                    shards.getShardsState(false).first().local.versionCode).apply()
            } ?: Log.w(TAG, "Update window ended before the downloads finished")
        } catch (e: Exception) {
            Log.e(TAG, "Database update failed", e)
        } finally {
            if (!force) jobs.setShouldExpediteJobs(false)
            // Let the last download job hand over its files before the network goes away
            delay(5_000)
            closeWindow()
            keepAlive?.close()
            running.value = false
            notifyApi()
        }
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
