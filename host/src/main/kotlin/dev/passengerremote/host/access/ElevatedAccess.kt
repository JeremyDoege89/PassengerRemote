package dev.passengerremote.host.access

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import dev.passengerremote.protocol.AccessTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.coroutines.resume

/**
 * Optional extra privilege, and the single place that knows how it was obtained.
 *
 * The app is fully usable without any of this. Everything here exists to make a
 * small number of features *more reliable*, never to gate a feature behind
 * setup the user didn't ask for -- see [CapabilityReporter] for which features
 * those are and, just as importantly, which ones no amount of privilege fixes.
 */
class ElevatedAccess(private val context: Context) {

    enum class ShizukuState {
        /** The Shizuku app isn't on the device. */
        NOT_INSTALLED,

        /** Installed, but its service isn't running (it stops on every reboot). */
        NOT_RUNNING,

        /** Running, but hasn't been told this app may use it. */
        NEEDS_PERMISSION,

        /** Too old to host a user service (pre-v11). */
        TOO_OLD,

        /** Ready to use. */
        READY,
    }

    private val _shizukuState = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val shizukuState: StateFlow<ShizukuState> = _shizukuState.asStateFlow()

    private val _tier = MutableStateFlow(AccessTier.ROOTLESS)
    val tier: StateFlow<AccessTier> = _tier.asStateFlow()

    /**
     * Human-readable result of the last verification, for the console.
     *
     * Without this there is no way for the user to tell whether granting Shizuku
     * actually achieved anything -- the first build offered a permission prompt
     * and then said nothing, which is indistinguishable from broken.
     */
    private val _verification = MutableStateFlow<String?>(null)
    val verification: StateFlow<String?> = _verification.asStateFlow()

    @Volatile
    private var service: IElevatedService? = null

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let { IElevatedService.Stub.asInterface(it) }
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refresh()
        }
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, ElevatedService::class.java.name))
            .daemon(false)
            .processNameSuffix("elevated")
            .debuggable(false)
            .version(SERVICE_VERSION)
    }

    fun start() {
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
        refresh()
    }

    fun stop() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        if (service != null) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
            service = null
        }
    }

    fun refresh() {
        _shizukuState.value = detectShizuku()
        _tier.value = when {
            service != null -> AccessTier.SHELL
            isRootAvailable() -> AccessTier.ROOT
            else -> AccessTier.ROOTLESS
        }
    }

    private fun detectShizuku(): ShizukuState {
        if (!isShizukuInstalled()) return ShizukuState.NOT_INSTALLED
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return ShizukuState.NOT_RUNNING
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) return ShizukuState.TOO_OLD
        val granted = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED
        return if (granted) ShizukuState.READY else ShizukuState.NEEDS_PERMISSION
    }

    private fun isShizukuInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Opens Shizuku's own permission prompt. Safe to call when already granted. */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { Log.w(TAG, "requestPermission failed", it) }
    }

    /**
     * Connects if Shizuku is ready, and reports what we ended up as.
     *
     * Called on every resume so the tier reflects reality without the user having
     * to press anything: granting Shizuku permission should be the last step, not
     * the second-to-last.
     */
    suspend fun verify(): String {
        val state = detectShizuku()
        if (state != ShizukuState.READY) {
            val message = when (state) {
                ShizukuState.NOT_INSTALLED -> "Shizuku isn't installed."
                ShizukuState.NOT_RUNNING -> "Shizuku is installed but its service isn't running."
                ShizukuState.NEEDS_PERMISSION -> "Shizuku hasn't granted this app permission yet."
                ShizukuState.TOO_OLD -> "This Shizuku version is too old to host a helper."
                ShizukuState.READY -> ""
            }
            _verification.value = message
            refresh()
            return message
        }
        if (!connect()) {
            _verification.value = "Shizuku is running, but the helper wouldn't start."
            return _verification.value!!
        }
        val identity = runCatching { service?.runForOutput("id -un; id -u") }
            .getOrNull()
            .orEmpty()
            .lines()
            .filter { it.isNotBlank() }
        val message = if (identity.isEmpty()) {
            "Helper started but returned nothing."
        } else {
            "Connected. Commands run as ${identity.joinToString(" / ")}."
        }
        _verification.value = message
        refresh()
        return message
    }

    /**
     * Binds the privileged helper. Returns false (rather than throwing) for every
     * reason it might not work, because none of them are errors -- the app is
     * expected to run without this.
     */
    suspend fun connect(): Boolean {
        if (service != null) return true
        if (detectShizuku() != ShizukuState.READY) return false
        val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val once = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        connection.onServiceConnected(name, binder)
                        if (continuation.isActive) continuation.resume(service != null)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        connection.onServiceDisconnected(name)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                runCatching { Shizuku.bindUserService(userServiceArgs, once) }
                    .onFailure {
                        Log.w(TAG, "bindUserService failed", it)
                        if (continuation.isActive) continuation.resume(false)
                    }
            }
        }
        refresh()
        return bound == true
    }

    /**
     * Runs a command at whatever privilege is available. Returns false if there
     * is none, or if the command failed -- callers fall back to the rootless path.
     */
    suspend fun runCommand(command: String): Boolean {
        service?.let { helper ->
            return runCatching { helper.runCommand(command) == 0 }
                .onFailure { Log.w(TAG, "elevated command failed", it) }
                .getOrDefault(false)
        }
        if (connect()) {
            return runCatching { service?.runCommand(command) == 0 }.getOrDefault(false)
        }
        if (isRootAvailable()) return runAsRoot(command)
        return false
    }

    private fun runAsRoot(command: String): Boolean = runCatching {
        Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
    }.getOrDefault(false)

    private fun isRootAvailable(): Boolean =
        SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }

    companion object {
        private const val TAG = "ElevatedAccess"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SERVICE_VERSION = 1
        private const val BIND_TIMEOUT_MS = 8_000L
        const val PERMISSION_REQUEST_CODE = 4021

        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/su",
        )
    }
}
