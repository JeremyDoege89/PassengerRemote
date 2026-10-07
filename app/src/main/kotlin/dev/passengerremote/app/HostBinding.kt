package dev.passengerremote.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.passengerremote.host.service.RemoteHostService

/**
 * Binds the host service for as long as the calling composable is around.
 *
 * Both the console and the remote screen need the same running host, so the
 * service is started (not just bound) and outlives either of them.
 */
@Composable
fun rememberHostService(): State<RemoteHostService?> {
    val context = LocalContext.current
    val holder = remember { mutableStateOf<RemoteHostService?>(null) }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                holder.value = (binder as? RemoteHostService.LocalBinder)?.service
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                holder.value = null
            }
        }
        RemoteHostService.start(context)
        RemoteHostService.bind(context, connection)
        onDispose {
            runCatching { context.unbindService(connection) }
            holder.value = null
        }
    }
    return holder
}

fun Context.hasNotificationAccess(): Boolean =
    dev.passengerremote.host.media.NotificationAccess.isGranted(this)
