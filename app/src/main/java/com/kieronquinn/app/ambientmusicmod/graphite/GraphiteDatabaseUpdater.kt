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
 *     conditions for scheduled updates), wait until the downloads finish or the window ends,
 *  4. close the window: network permission revoked, window cleared.
 */
object GraphiteDatabaseUpdater : KoinComponent {

    private const val TAG = "GraphiteDbUpdate"
    private const val MUSICDB = "org.graphite.musicdb"
    private const val INTERNET = "android.permission.INTERNET"
    const val SETTING_UPDATE_UNTIL = "graphite_nowplaying_update_until"

    private const val MANUAL_WINDOW = 60 * 60 * 1000L
    private const val SCHEDULED_WINDOW = 6 * 60 * 60 * 1000L
    private const val DOWNLOAD_START_TIMEOUT = 2 * 60 * 1000L

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

    /** Makes sure the downloader has no network when no update runs (called at start) */
    fun closeWindow() {
        setDownloaderNetwork(false)
        Settings.Global.putLong(context.contentResolver, SETTING_UPDATE_UNTIL, 0L)
    }

    private suspend fun run(force: Boolean, window: Long) = lock.withLock {
        running.value = true
        notifyApi()
        try {
            Settings.Global.putLong(
                context.contentResolver, SETTING_UPDATE_UNTIL, System.currentTimeMillis() + window
            )
            setDownloaderNetwork(true)
            val state = shards.getShardsState(true).first()
            val target = state.remote?.takeIf { state.updateAvailable }
            if (target != null) {
                deviceConfig.indexManifest.set(target.url)
            }
            if (force) jobs.forceExpediteJobs() else jobs.setShouldExpediteJobs(true)
            withTimeoutOrNull(window) {
                if (target != null) {
                    // Done when the engine has installed the new version. (The engine always keeps a
                    // charging+idle manifest recheck pending, so "no downloads left" never happens.)
                    while (shards.getShardsState(false).first().local.versionCode != target.versionCode) {
                        delay(10_000)
                    }
                } else {
                    // Already current: only give the engine time to fetch anything it's missing
                    delay(DOWNLOAD_START_TIMEOUT)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Database update failed", e)
        } finally {
            if (!force) jobs.setShouldExpediteJobs(false)
            // Let the last download job hand over its files before the network goes away
            delay(5_000)
            closeWindow()
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
