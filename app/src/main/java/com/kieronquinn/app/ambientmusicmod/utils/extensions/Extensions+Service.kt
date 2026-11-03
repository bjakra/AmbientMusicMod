package com.kieronquinn.app.ambientmusicmod.utils.extensions

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import com.kieronquinn.app.ambientmusicmod.components.notifications.NotificationId

fun Service.startForeground(notificationId: NotificationId, notification: Notification) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Graphite: this process records itself (AMM's shell user did), and background
            // microphone access needs a microphone type foreground service
            startForeground(
                notificationId.ordinal,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        }else{
            startForeground(notificationId.ordinal, notification)
        }
    }catch (e: Exception) {
        //Caches ForegroundServiceStartNotAllowedException on S+ when unable to startForeground
    }
}