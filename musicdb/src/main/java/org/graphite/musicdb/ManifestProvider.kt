package org.graphite.musicdb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.google.android.`as`.oss.http.service.GraphiteNetworkGate
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the list of the newest music database for Graphite Now Playing, the file Ambient Music Mod read with its
 * own network access (KieronQuinn/AmbientMusicManifest). Only this app has network, and only during an update window
 * (see GraphiteNetworkGate). Protected by org.graphite.nowplaying.ACCESS_SERVICE.
 */
class ManifestProvider : ContentProvider() {

    companion object {
        private const val BASE = "https://raw.githubusercontent.com/KieronQuinn/AmbientMusicManifest/main/"
        private val FILES = setOf("release.json", "release_v3.json")
    }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "fetch" || arg !in FILES) return null
        val url = BASE + arg
        if (!GraphiteNetworkGate.isAllowed(url)) return null
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            try {
                if (connection.responseCode != 200) return null
                Bundle().apply { putString("body", connection.inputStream.bufferedReader().readText()) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
