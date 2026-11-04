package com.kieronquinn.app.ambientmusicmod.graphite

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.util.Log

/**
 * Graphite: the runtime permissions Now Playing needs to listen, the same set as
 * default-permissions-nowplaying.xml in the ROM. Android only applies those default grants when the
 * build fingerprint changes, so they're granted again when the user turns Now Playing on in Graphite
 * settings, the explicit consent for it. Without them the core can't record and the engine, missing
 * READ_PHONE_STATE, assumes a call is in progress and skips every recognition.
 */
object GraphitePermissions {
    private const val TAG = "GraphitePermissions"
    private const val ENGINE = "com.kieronquinn.app.pixelambientmusic"

    fun grantForListening(context: Context) {
        grant(context, context.packageName, Manifest.permission.RECORD_AUDIO)
        grant(context, context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        grant(context, ENGINE, Manifest.permission.RECORD_AUDIO)
        grant(context, ENGINE, Manifest.permission.READ_PHONE_STATE)
        grant(context, ENGINE, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun grant(context: Context, packageName: String, permission: String) {
        val pm = context.packageManager
        if (pm.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED) return
        // Hidden PackageManager API; the core holds GRANT_RUNTIME_PERMISSIONS as a platform-signed app
        runCatching {
            pm.javaClass.getMethod(
                "grantRuntimePermission", String::class.java, String::class.java, UserHandle::class.java,
            ).invoke(pm, packageName, permission, Process.myUserHandle())
        }.onFailure { Log.e(TAG, "Couldn't grant $permission to $packageName", it) }
    }
}
