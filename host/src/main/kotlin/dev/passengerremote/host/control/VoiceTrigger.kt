package dev.passengerremote.host.control

import android.content.Context
import android.content.Intent
import dev.passengerremote.host.access.ElevatedAccess

/**
 * Voice trigger, best available route first.
 *
 * KEYCODE_VOICE_ASSIST (231) is what the steering-wheel button sends. AOSP
 * documents it as "not delivered to applications", so it can only ever be
 * *dispatched* from a shell, never received -- which is why this needs Shizuku
 * or root and cannot be done rootless at all.
 *
 * Without that, ACTION_VOICE_COMMAND is the closest match to the car's mic
 * button but is known to show the listening UI without actually capturing on
 * some builds, and ACTION_ASSIST reliably opens the full-screen assistant
 * instead of the compact bar. Both are tried, in that order, so the feature
 * always does *something* -- elevated access makes it dependable rather than
 * making it exist.
 */
class VoiceTrigger(
    private val context: Context,
    private val elevated: ElevatedAccess? = null,
) {

    suspend fun trigger(): Boolean {
        if (elevated != null && elevated.tier.value != dev.passengerremote.protocol.AccessTier.ROOTLESS) {
            if (elevated.runCommand("input keyevent $KEYCODE_VOICE_ASSIST")) return true
        }
        val voiceCommand = Intent(Intent.ACTION_VOICE_COMMAND)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch(voiceCommand)) return true
        val assist = Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(assist)
    }

    private fun launch(intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private companion object {
        /** KeyEvent.KEYCODE_VOICE_ASSIST. Not a public constant before API 31. */
        const val KEYCODE_VOICE_ASSIST = 231
    }
}
