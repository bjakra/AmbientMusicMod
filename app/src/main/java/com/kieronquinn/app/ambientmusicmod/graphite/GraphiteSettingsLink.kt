package com.kieronquinn.app.ambientmusicmod.graphite

import android.app.Activity
import android.content.Intent

/**
 *  Graphite: this app has no screens of its own; Graphite settings has the Now Playing page. AMM's
 *  activities stay as entry points (notifications, the Quick Settings tile and shortcuts point at
 *  them) and forward to that page, or to its "Recognise now".
 */
object GraphiteSettingsLink {

    private const val PARTS = "org.graphite.parts"
    private const val EXTRA_RECOGNISE = "org.graphite.nowplaying.RECOGNISE"

    /** Opens Graphite settings' Now Playing page; false if Graphite settings isn't there */
    fun open(activity: Activity, recognise: Boolean = false): Boolean {
        val intent = Intent().setClassName(PARTS, "$PARTS.GraphiteActivity")
            .putExtra("$PARTS.FRAGMENT", "$PARTS.nowplaying.NowPlayingFragment")
            .putExtra("$PARTS.TITLE", "Now Playing")
            .putExtra(EXTRA_RECOGNISE, recognise)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return runCatching { activity.startActivity(intent) }.isSuccess
    }
}
