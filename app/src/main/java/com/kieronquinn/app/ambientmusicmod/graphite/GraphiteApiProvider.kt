package com.kieronquinn.app.ambientmusicmod.graphite

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.kieronquinn.app.ambientmusicmod.repositories.BaseSettingsRepository.AmbientMusicModSetting
import com.kieronquinn.app.ambientmusicmod.repositories.DeviceConfigRepository
import com.kieronquinn.app.ambientmusicmod.repositories.RemoteSettingsRepository
import com.kieronquinn.app.ambientmusicmod.repositories.RemoteSettingsRepository.SettingsState
import com.kieronquinn.app.ambientmusicmod.repositories.SettingsRepository
import com.kieronquinn.app.pixelambientmusic.model.SettingsStateChange
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 *  Graphite: the interface Graphite settings (org.graphite.parts) uses for its Now Playing page.
 *  Graphite settings shows and changes everything, this app keeps Ambient Music Mod's logic: every
 *  setting here is the same repository setting AMM's own screens used, so side effects stay the same.
 *
 *  Protected by org.graphite.nowplaying.ACCESS_SERVICE (signature). Changes are announced on
 *  [URI_STATE].
 */
class GraphiteApiProvider : ContentProvider(), KoinComponent {

    companion object {
        const val AUTHORITY = "org.graphite.nowplaying.api"
        val URI_STATE: Uri = Uri.parse("content://$AUTHORITY/state")

        const val METHOD_GET_STATE = "getState"
        const val METHOD_SET_ENABLED = "setEnabled"
        const val METHOD_GET_SETTINGS = "getSettings"
        const val METHOD_SET_SETTING = "setSetting"

        const val KEY_TYPE = "type"
        const val KEY_VALUE = "value"
        const val KEY_OPTIONS = "options"
        const val KEY_OPTION_TITLES = "optionTitles"
    }

    private val settings by inject<SettingsRepository>()
    private val deviceConfig by inject<DeviceConfigRepository>()
    private val remoteSettings by inject<RemoteSettingsRepository>()

    /** The settings Graphite settings shows, by the names AMM's repositories use */
    private val registry: Map<String, AmbientMusicModSetting<*>> by lazy {
        mapOf(
            "recognitionPeriod" to settings.recognitionPeriod,
            "recognitionPeriodAdaptive" to settings.recognitionPeriodAdaptive,
            "recognitionBuffer" to settings.recognitionBuffer,
            "triggerWhenScreenOn" to settings.triggerWhenScreenOn,
            "bedtimeModeEnabled" to settings.bedtimeModeEnabled,
            "bedtimeModeStart" to settings.bedtimeModeStart,
            "bedtimeModeEnd" to settings.bedtimeModeEnd,
            "automaticMusicDatabaseUpdates" to settings.automaticMusicDatabaseUpdates,
            "periodicBackupEnabled" to settings.periodicBackupEnabled,
            "periodicBackupInterval" to settings.periodicBackupInterval,
            "lockscreenOverlayClicked" to settings.lockscreenOverlayClicked,
            "superpacksRequireWiFi" to deviceConfig.superpacksRequireWiFi,
            "superpacksRequireCharging" to deviceConfig.superpacksRequireCharging,
            "deviceCountry" to deviceConfig.deviceCountry,
            "extraLanguages" to deviceConfig.extraLanguages,
            "showAlbumArt" to deviceConfig.showAlbumArt,
            "historySummaryDays" to deviceConfig.historySummaryDays,
            "recordingGain" to deviceConfig.recordingGain,
            "alternativeEncoding" to deviceConfig.alternativeEncoding,
            "runOnSmallCores" to deviceConfig.runOnSmallCores,
            "cacheShardEnabled" to deviceConfig.cacheShardEnabled,
            "enableLogging" to deviceConfig.enableLogging
        )
    }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = runBlocking {
        when (method) {
            METHOD_GET_STATE -> getState()
            METHOD_SET_ENABLED -> {
                val enabled = extras?.getBoolean(KEY_VALUE) ?: return@runBlocking null
                // Graphite settings replaces AMM's setup, so turning it on counts as set up
                if (enabled) settings.hasSeenSetup.set(true)
                remoteSettings.commitChanges(SettingsStateChange(mainEnabled = enabled))
                notifyChanged()
                null
            }
            METHOD_GET_SETTINGS -> Bundle().apply {
                registry.forEach { (key, setting) -> putBundle(key, describe(setting)) }
            }
            METHOD_SET_SETTING -> {
                val setting = registry[arg ?: return@runBlocking null] ?: return@runBlocking null
                setValue(setting, extras ?: return@runBlocking null)
                notifyChanged()
                null
            }
            else -> null
        }
    }

    private suspend fun getState(): Bundle = Bundle().apply {
        when (val state = remoteSettings.getRemoteSettings().filterNotNull().first()) {
            is SettingsState.Available -> {
                putString("state", "available")
                putBoolean("mainEnabled", state.mainEnabled)
                putBoolean("notificationsEnabled", state.notificationsEnabled)
                putString("banner", state.bannerMessage?.javaClass?.simpleName)
                state.lastRecognisedSong?.let {
                    putString("lastTrack", it.track)
                    putString("lastArtist", it.artist)
                    putLong("lastTimestamp", it.timestamp)
                }
            }
            SettingsState.NoPAM -> putString("state", "noEngine")
            SettingsState.NoShizuku -> putString("state", "noService")
            SettingsState.NotSetup -> putString("state", "notSetup")
        }
    }

    private suspend fun describe(setting: AmbientMusicModSetting<*>): Bundle = Bundle().apply {
        when (val value = setting.get()) {
            is Boolean -> { putString(KEY_TYPE, "bool"); putBoolean(KEY_VALUE, value) }
            is Int -> { putString(KEY_TYPE, "int"); putInt(KEY_VALUE, value) }
            is Long -> { putString(KEY_TYPE, "long"); putLong(KEY_VALUE, value) }
            is Float -> { putString(KEY_TYPE, "float"); putFloat(KEY_VALUE, value) }
            is String -> { putString(KEY_TYPE, "string"); putString(KEY_VALUE, value) }
            is Enum<*> -> {
                putString(KEY_TYPE, "enum")
                putString(KEY_VALUE, value.name)
                val options = value.declaringJavaClass.enumConstants.orEmpty()
                putStringArray(KEY_OPTIONS, options.map { it.name }.toTypedArray())
                // AMM's own labels, resolved here so Graphite shows exactly the same choices
                putStringArray(KEY_OPTION_TITLES, options.map { titleOf(it) }.toTypedArray())
            }
            else -> putString(KEY_TYPE, "unsupported")
        }
    }

    private fun titleOf(option: Enum<*>): String {
        val res = runCatching {
            option.javaClass.getMethod("getTitle").invoke(option) as Int
        }.getOrNull() ?: return option.name
        return context?.getString(res) ?: option.name
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun setValue(setting: AmbientMusicModSetting<*>, extras: Bundle) {
        val target = setting as AmbientMusicModSetting<Any>
        val value: Any = when (val current = setting.get()) {
            is Boolean -> extras.getBoolean(KEY_VALUE)
            is Int -> extras.getInt(KEY_VALUE)
            is Long -> extras.getLong(KEY_VALUE)
            is Float -> extras.getFloat(KEY_VALUE)
            is String -> extras.getString(KEY_VALUE) ?: return
            is Enum<*> -> {
                val name = extras.getString(KEY_VALUE) ?: return
                current.declaringJavaClass.enumConstants?.firstOrNull { it.name == name } ?: return
            }
            else -> return
        }
        target.set(value)
    }

    private fun notifyChanged() {
        context?.contentResolver?.notifyChange(URI_STATE, null)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ) = 0

}
