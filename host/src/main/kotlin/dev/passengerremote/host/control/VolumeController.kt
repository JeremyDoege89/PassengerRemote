package dev.passengerremote.host.control

import android.content.Context
import android.media.AudioManager
import dev.passengerremote.protocol.VolumeState

/**
 * Wraps STREAM_MUSIC volume.
 *
 * Whether this does anything audible is a property of the car, not of the code:
 * many head units run their own amplifier and treat the phone as a fixed-level
 * source. [VolumeState.fixed] carries AudioManager.isVolumeFixed() to the remote
 * so the slider can be disabled with an explanation instead of silently doing
 * nothing.
 */
class VolumeController(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)

    fun state(): VolumeState = VolumeState(
        index = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        fixed = audio.isVolumeFixed,
    )

    val max: Int get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    val isFixed: Boolean get() = audio.isVolumeFixed

    fun set(index: Int) {
        if (audio.isVolumeFixed) return
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            index.coerceIn(0, max),
            0, // no system volume UI: the remote already shows the level
        )
    }

    fun adjust(delta: Int) {
        if (audio.isVolumeFixed) return
        set(audio.getStreamVolume(AudioManager.STREAM_MUSIC) + delta)
    }

    companion object {
        /** Ships verbatim in the UI wherever the volume slider appears. */
        const val FIXED_VOLUME_DISCLAIMER =
            "Volume control works on some vehicles only. Many car head units manage " +
                "their own amplifier and volume independently of your phone, so this " +
                "slider may have no audible effect in your car. It is most likely to " +
                "work with wired Android Auto and least likely to work when your car " +
                "controls volume from its own dial or over Bluetooth. This is a " +
                "limitation of your vehicle, not a bug in the app."
    }
}
