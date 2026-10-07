package dev.passengerremote.host.access

import android.util.Log

/**
 * The privileged half. Instantiated by Shizuku inside a shell-uid process, so
 * `this` code runs with adb-level rights while the rest of the app does not.
 *
 * Kept deliberately tiny: one entry point, no state, nothing that could outlive
 * a command. Anything richer belongs behind a new AIDL method rather than a
 * general "run anything the app sends" surface -- but note that this IS that
 * surface today, which is why nothing outside [ElevatedAccess] may call it.
 */
class ElevatedService : IElevatedService.Stub {

    @Suppress("unused") // Shizuku instantiates via the no-arg constructor.
    constructor()

    override fun destroy() {
        System.exit(0)
    }

    override fun runCommand(command: String): Int = try {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        process.waitFor()
    } catch (e: Exception) {
        Log.w(TAG, "command failed: $command", e)
        -1
    }

    override fun runForOutput(command: String): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$command 2>&1"))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output.trim()
    } catch (e: Exception) {
        Log.w(TAG, "command failed: $command", e)
        ""
    }

    private companion object {
        const val TAG = "ElevatedService"
    }
}
