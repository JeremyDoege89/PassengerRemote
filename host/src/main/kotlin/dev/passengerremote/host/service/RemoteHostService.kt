package dev.passengerremote.host.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.passengerremote.host.HostEngine
import dev.passengerremote.host.R
import dev.passengerremote.protocol.RemotePolicy
import dev.passengerremote.protocol.RemoteRole
import dev.passengerremote.transport.LoopbackLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Long-lived owner of everything privileged.
 *
 * Declared as connectedDevice|mediaPlayback: connectedDevice covers a paired
 * passenger phone, and mediaPlayback covers the case this feature added -- the
 * driver's own phone acting as its own remote, where there is no connected
 * device at all and the only justification is that we are driving media.
 */
class RemoteHostService : LifecycleService() {

    lateinit var engine: HostEngine
        private set

    private var selfRemoteId: String? = null

    private val _startError = MutableStateFlow<String?>(null)

    /** Non-null when the service could not go foreground. Shown by the console. */
    val startError: StateFlow<String?> = _startError.asStateFlow()

    inner class LocalBinder : Binder() {
        val service: RemoteHostService get() = this@RemoteHostService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        engine = HostEngine(applicationContext, lifecycleScope)
        engine.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), foregroundTypes())
            _startError.value = null
        } catch (e: Exception) {
            // A foreground-service type/permission mismatch is a manifest bug, but
            // it must not crash-loop the launcher. Degrade to a bound service --
            // self-remote still works while the remote screen is in front, it just
            // won't survive being backgrounded -- and let the console say why.
            Log.e(TAG, "startForeground failed", e)
            _startError.value = e.message ?: e.javaClass.simpleName
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }

    // -- self-remote -------------------------------------------------------

    /**
     * Attaches this phone's own remote UI over the loopback link.
     *
     * Idempotent: the remote Activity can come and go (rotation, backgrounding)
     * without the host tearing the attachment down, so state survives.
     */
    fun attachSelfRemote(role: RemoteRole, policy: RemotePolicy): String {
        selfRemoteId?.let { existing ->
            engine.setPolicy(existing, policy)
            return existing
        }
        val name = when (role) {
            RemoteRole.HANDOFF -> "This phone (handed over)"
            else -> "This phone"
        }
        return engine.attach(LoopbackLink.shared().hostEnd, role, name, policy)
            .also { selfRemoteId = it }
    }

    /** Re-applies a policy to the self remote, e.g. when handoff mode starts. */
    fun setSelfRemotePolicy(policy: RemotePolicy) {
        selfRemoteId?.let { engine.setPolicy(it, policy) }
    }

    fun detachSelfRemote() {
        selfRemoteId?.let { engine.detach(it) }
        selfRemoteId = null
        LoopbackLink.shared().resetReplay()
    }

    /**
     * Picks the foreground-service type to actually claim.
     *
     * mediaPlayback is always true: the service exists to drive media. connectedDevice
     * is only added when a Bluetooth permission is genuinely granted, because the
     * system validates every type it is handed and throws otherwise -- which is
     * exactly how this crashed on launch the first time. A self-remote-only user
     * has no connected device and should not be claiming one.
     */
    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        val bluetoothGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (bluetoothGranted) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return types
    }

    /** Re-evaluates the claimed types, e.g. after the user grants Bluetooth. */
    fun refreshForegroundType() {
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(), foregroundTypes()) }
            .onFailure { Log.w(TAG, "could not update foreground type", it) }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.host_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.host_notification_title))
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setOngoing(true)
            .apply { launch?.let { setContentIntent(it) } }
            .build()
    }

    companion object {
        private const val TAG = "RemoteHostService"
        private const val CHANNEL_ID = "remote_host"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, RemoteHostService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteHostService::class.java))
        }

        /**
         * Binds and hands back the running service. Callers must unbind.
         */
        fun bind(context: Context, connection: ServiceConnection): Boolean {
            val intent = Intent().setComponent(
                ComponentName(context, RemoteHostService::class.java),
            )
            return context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }
}
