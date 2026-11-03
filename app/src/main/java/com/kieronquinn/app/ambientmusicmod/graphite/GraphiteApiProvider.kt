package com.kieronquinn.app.ambientmusicmod.graphite

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.kieronquinn.app.ambientmusicmod.repositories.BaseSettingsRepository.AmbientMusicModSetting
import com.kieronquinn.app.ambientmusicmod.repositories.DeviceConfigRepository
import com.kieronquinn.app.ambientmusicmod.repositories.RecognitionRepository
import com.kieronquinn.app.ambientmusicmod.repositories.RecognitionRepository.RecognitionState
import com.kieronquinn.app.ambientmusicmod.repositories.WidgetRepository
import com.kieronquinn.app.ambientmusicmod.service.AmbientMusicModForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.kieronquinn.app.ambientmusicmod.repositories.RemoteSettingsRepository
import com.kieronquinn.app.ambientmusicmod.repositories.RemoteSettingsRepository.SettingsState
import com.kieronquinn.app.ambientmusicmod.repositories.SettingsRepository
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsRepository
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
        const val METHOD_UPDATE_DATABASE = "updateDatabase"
        const val METHOD_GET_DATABASE = "getDatabase"
        const val METHOD_GET_COUNTRIES = "getCountries"
        const val METHOD_SET_COUNTRY = "setCountry"
        const val METHOD_SET_EXTRA_COUNTRIES = "setExtraCountries"
        /** AMM's MAX_EXTRA_LANGUAGE_LIMIT */
        private const val MAX_EXTRA_COUNTRIES = 2
        const val METHOD_RECOGNISE = "recognise"
        const val METHOD_CANCEL_RECOGNITION = "cancelRecognition"
        const val METHOD_GET_RECOGNITION = "getRecognition"
        /** The song shown now (track, artist, endTime), empty when none; changes on .../song */
        const val METHOD_GET_SONG = "getSong"
        val URI_RECOGNITION: Uri = Uri.parse("content://$AUTHORITY/recognition")

        /**
         *  Announces every newly recognised song on [URI_STATE], so Graphite's lock screen line and
         *  At a Glance card can follow it. Started from the Application once Koin is up.
         */
        fun watchRecognitions(context: android.content.Context) {
            val repository = org.koin.java.KoinJavaComponent.get<RecognitionRepository>(
                RecognitionRepository::class.java
            )
            MainScope().launch {
                repository.getLatestRecognition().collect {
                    context.contentResolver.notifyChange(URI_STATE, null)
                }
            }
        }

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
            "automaticMusicDatabaseUpdatesInterval" to settings.automaticMusicDatabaseUpdatesInterval,
            "periodicBackupEnabled" to settings.periodicBackupEnabled,
            "periodicBackupInterval" to settings.periodicBackupInterval,
            "lockscreenOverlayClicked" to settings.lockscreenOverlayClicked,
            "graphiteShowSong" to settings.graphiteShowSong,
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
            METHOD_UPDATE_DATABASE -> { GraphiteDatabaseUpdater.updateNow(); null }
            METHOD_GET_COUNTRIES -> getCountries()
            METHOD_SET_COUNTRY -> {
                deviceConfig.deviceCountry.set(arg.orEmpty())
                notifyChanged()
                null
            }
            METHOD_SET_EXTRA_COUNTRIES ->
                setExtraCountries(extras?.getStringArray(KEY_VALUE)?.filterNotNull().orEmpty())
            METHOD_GET_DATABASE -> getDatabase()
            METHOD_RECOGNISE -> { startRecognition(); null }
            METHOD_CANCEL_RECOGNITION -> { recognitionJob?.cancel(); null }
            METHOD_GET_RECOGNITION -> Bundle(recognition)
            METHOD_GET_SONG -> Bundle(GraphiteNowPlayingSong.current)
            else -> null
        }
    }

    private val recognitionRepository by inject<RecognitionRepository>()
    private val widgetRepository by inject<WidgetRepository>()
    private val scope = MainScope()
    private var recognitionJob: Job? = null
    @Volatile private var recognition = Bundle()

    /**
     *  "Recognise now", as AMM's recognition screen did for the on-device engine: the same request,
     *  the widget is told about every state and a recognised song goes to the service (lock screen,
     *  notification). Graphite's page can't play the recording back, so no audio is kept with it.
     */
    private fun startRecognition() {
        recognitionJob?.cancel()
        recognitionJob = scope.launch {
            recognitionRepository.requestRecognition(false).collect { state ->
                widgetRepository.notifyRecognitionState(state)
                recognition = Bundle().apply {
                    when (state) {
                        is RecognitionState.Recording -> putString("phase", "recording")
                        is RecognitionState.Recognising -> putString("phase", "recognising")
                        is RecognitionState.Recognised -> {
                            AmbientMusicModForegroundService.sendManualRecognition(state)
                            putString("phase", "recognised")
                            putString("track", state.recognitionResult.trackName)
                            putString("artist", state.recognitionResult.artist)
                        }
                        is RecognitionState.Failed -> {
                            putString("phase", "failed")
                            putString("reason", state.recognitionFailure.failureReason.javaClass.simpleName)
                        }
                        is RecognitionState.Error -> {
                            putString("phase", "error")
                            putString("reason", state.errorReason.name)
                        }
                    }
                }
                context?.contentResolver?.notifyChange(URI_RECOGNITION, null)
                if (state is RecognitionState.Recognised || state is RecognitionState.Failed ||
                    state is RecognitionState.Error) {
                    cancel()
                }
            }
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

    private val shards by inject<ShardsRepository>()

    /** AMM's country pickers: every database country, the chosen one (empty: automatic), extras */
    private suspend fun getCountries(): Bundle = Bundle().apply {
        val all = ShardsRepository.ShardCountry.values()
        putStringArray("codes", all.map { it.code }.toTypedArray())
        putStringArray("names", all.map { country ->
            context?.getString(country.countryName) ?: country.code
        }.toTypedArray())
        putString("country", deviceConfig.deviceCountry.get())
        // As AMM's country entry: some countries have several codes ("us,xa"), the region gives one
        putString("primary", runCatching {
            ShardsRepository.ShardCountry.forCode(deviceConfig.getPrimaryLanguage()).code
        }.getOrDefault(deviceConfig.getPrimaryLanguage()))
        putStringArray("extras", extraCountries().map { it.code }.toTypedArray())
    }

    private suspend fun extraCountries(): List<ShardsRepository.ShardCountry> {
        val primary = ShardsRepository.ShardCountry.forCode(deviceConfig.getPrimaryLanguage())
        return deviceConfig.getExtraLanguages().map { ShardsRepository.ShardCountry.forCode(it) }
            .filterNot { it == primary }
    }

    /** AMM's extra country picker logic: at most two, never the primary one */
    private suspend fun setExtraCountries(codes: List<String>): Bundle {
        val primary = ShardsRepository.ShardCountry.forCode(deviceConfig.getPrimaryLanguage())
        val countries = codes.map { ShardsRepository.ShardCountry.forCode(it) }
            .filterNot { it == primary }.distinct()
        if (countries.size > MAX_EXTRA_COUNTRIES) {
            return Bundle().apply { putBoolean("tooMany", true) }
        }
        deviceConfig.extraLanguages.set(countries.joinToString(",") { it.code })
        deviceConfig.extraLanguageLimit.set(countries.size)
        //Trigger a primary change to force a reload of Now Playing shards (as AMM)
        deviceConfig.deviceCountry.notifyChange()
        notifyChanged()
        return Bundle()
    }

    /** The installed database (AMM's Updates screen), without asking the network */
    private suspend fun getDatabase(): Bundle = Bundle().apply {
        putBoolean("updating", GraphiteDatabaseUpdater.running.value)
        runCatching { shards.getCurrentDownloads().first() }.getOrNull()?.let { putInt("downloads", it) }
        val state = runCatching { shards.getShardsState(false).first() }.getOrNull() ?: return@apply
        putInt("version", state.local.versionCode)
        putString("date", state.local.date.toLocalDate().toString())
        putInt("tracks", state.local.trackCount)
        putInt("personalisedTracks", state.local.personalisedTrackCount)
        putStringArray("countries", state.local.selectedCountries.map { it.name }.toTypedArray())
        state.remote?.let {
            putInt("remoteVersion", it.versionCode)
            putString("remoteDate", it.date.toLocalDate().toString())
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
