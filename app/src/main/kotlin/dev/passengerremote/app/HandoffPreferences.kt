package dev.passengerremote.app

import android.content.Context
import dev.passengerremote.protocol.HandoffSettings

/**
 * Stores what the back seat is allowed to do.
 *
 * Plain SharedPreferences: this is five scalars read once per handoff, and the
 * alternative (DataStore) would add a dependency and an async read to a path
 * that has to be ready the instant the driver taps "hand it over".
 */
class HandoffPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("handoff", Context.MODE_PRIVATE)

    fun load(): HandoffSettings {
        val defaults = HandoffSettings()
        return HandoffSettings(
            volumeHeadroom = prefs.getInt(KEY_HEADROOM, defaults.volumeHeadroom),
            allowVolume = prefs.getBoolean(KEY_VOLUME, defaults.allowVolume),
            allowSeek = prefs.getBoolean(KEY_SEEK, defaults.allowSeek),
            allowQueueJump = prefs.getBoolean(KEY_QUEUE, defaults.allowQueueJump),
            maxSkipsPerMinute = prefs.getInt(KEY_SKIPS, defaults.maxSkipsPerMinute),
        )
    }

    fun save(settings: HandoffSettings) {
        prefs.edit()
            .putInt(KEY_HEADROOM, settings.volumeHeadroom)
            .putBoolean(KEY_VOLUME, settings.allowVolume)
            .putBoolean(KEY_SEEK, settings.allowSeek)
            .putBoolean(KEY_QUEUE, settings.allowQueueJump)
            .putInt(KEY_SKIPS, settings.maxSkipsPerMinute)
            .apply()
    }

    private companion object {
        const val KEY_HEADROOM = "volume_headroom"
        const val KEY_VOLUME = "allow_volume"
        const val KEY_SEEK = "allow_seek"
        const val KEY_QUEUE = "allow_queue_jump"
        const val KEY_SKIPS = "max_skips_per_minute"
    }
}
