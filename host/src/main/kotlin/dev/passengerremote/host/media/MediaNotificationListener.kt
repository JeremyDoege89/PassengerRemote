package dev.passengerremote.host.media

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Exists purely to be *enabled*.
 *
 * MediaSessionManager.getActiveSessions() normally needs the signature-level
 * MEDIA_CONTENT_CONTROL permission, which no third-party app can hold. The
 * documented alternative is to pass the ComponentName of an enabled
 * NotificationListenerService, which is what this class provides.
 *
 * It deliberately does not override onNotificationPosted/Removed. Reading
 * notification content is both unnecessary for media control and the thing that
 * makes the Play Console declaration for this permission hard to defend.
 */
class MediaNotificationListener : NotificationListenerService() {

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, MediaNotificationListener::class.java)
    }
}

object NotificationAccess {

    fun isGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val us = MediaNotificationListener.componentName(context)
        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == us
        }
    }

    /** Intent for the system screen where the user grants listener access. */
    fun settingsIntent() = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}
