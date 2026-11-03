package com.kieronquinn.app.ambientmusicmod.graphite

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.kieronquinn.app.ambientmusicmod.model.lockscreenoverlay.OverlayState

/**
 *  Graphite: the song currently shown, decided exactly like AMM's lock screen overlay (the service's
 *  overlay state: after a recognition until the estimated end of the track, cleared when the next
 *  recognition fails). Graphite's lock screen line and Lawnchair's At a Glance card show it through
 *  the API ([GraphiteApiProvider.METHOD_GET_SONG]); AMM drew it with an accessibility overlay.
 */
object GraphiteNowPlayingSong {

    val URI_SONG: Uri = Uri.parse("content://${GraphiteApiProvider.AUTHORITY}/song")

    @Volatile
    var current: Bundle = Bundle()
        private set

    fun publish(context: Context, state: OverlayState) {
        current = when (state) {
            is OverlayState.Shown -> Bundle().apply {
                putString("track", state.content.toString())
                state.contentAfterBullet?.let { putString("artist", it.toString()) }
                putLong("endTime", state.endTime)
            }
            else -> Bundle()
        }
        context.contentResolver.notifyChange(URI_SONG, null)
    }
}
