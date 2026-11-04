package com.kieronquinn.app.ambientmusicmod.components.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.kieronquinn.app.ambientmusicmod.R
import android.app.NotificationChannel as AndroidNotificationChannel

fun Context.createNotification(
    channel: NotificationChannel,
    builder: (NotificationCompat.Builder) -> Unit
): Notification {
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notificationChannel =
        AndroidNotificationChannel(
            channel.id,
            getString(channel.titleRes),
            channel.importance
        ).apply {
            description = getString(channel.descRes)
        }
    notificationManager.createNotificationChannel(notificationChannel)
    if (channel == NotificationChannel.FOREGROUND_SERVICE) {
        // Graphite: AMM's visible "running in the background" channel, replaced by a hidden one.
        // Android refuses while a foreground service still uses it; then it goes next time.
        runCatching { notificationManager.deleteNotificationChannel(LEGACY_FOREGROUND_SERVICE_CHANNEL) }
    }
    return NotificationCompat.Builder(this, channel.id).apply(builder).build()
}

enum class NotificationChannel(
    val id: String,
    val importance: Int,
    val titleRes: Int,
    val descRes: Int
) {
    /**
     * Graphite: Now Playing runs as a system service and is turned on and off in Graphite settings, so
     * its foreground service notification is hidden: the channel is created blocked (importance
     * none). The service keeps running; Android just shows nothing for it, and SystemUI doesn't list
     * it under active apps (patch 0460).
     */
    FOREGROUND_SERVICE (
        "graphite_background_service",
        NotificationManager.IMPORTANCE_NONE,
        R.string.notification_channel_service_foreground_title,
        R.string.notification_channel_service_foreground_subtitle
    ),
    UPDATES (
        "updates",
        NotificationManager.IMPORTANCE_HIGH,
        R.string.notification_channel_updates_title,
        R.string.notification_channel_updates_subtitle
    ),
    ERRORS (
        "errors",
        NotificationManager.IMPORTANCE_HIGH,
        R.string.notification_channel_errors_title,
        R.string.notification_channel_errors_subtitle
    ),
    WARNINGS (
        "warnings",
        NotificationManager.IMPORTANCE_HIGH,
        R.string.notification_channel_warnings_title,
        R.string.notification_channel_warnings_subtitle
    ),
    BACKUP (
        "backup",
        NotificationManager.IMPORTANCE_DEFAULT,
        R.string.notification_channel_backups_title,
        R.string.notification_channel_backups_subtitle
    ),
    DATABASE_DOWNLOAD (
        "database_download",
        NotificationManager.IMPORTANCE_DEFAULT,
        R.string.notification_channel_database_download_title,
        R.string.notification_channel_database_download_subtitle
    )
}

private const val LEGACY_FOREGROUND_SERVICE_CHANNEL = "foreground_service"

enum class NotificationId {
    BUFFER, //Foreground ID cannot be 0
    FOREGROUND_SERVICE,
    UPDATES,
    ERRORS,
    WARNINGS,
    BACKUP,
    DATABASE_DOWNLOAD
}