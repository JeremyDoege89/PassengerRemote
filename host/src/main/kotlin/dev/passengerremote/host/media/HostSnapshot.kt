package dev.passengerremote.host.media

import android.graphics.Bitmap
import dev.passengerremote.protocol.NowPlaying
import dev.passengerremote.protocol.QueueEntry

/** What the host currently knows about playback, before it becomes messages. */
sealed interface HostSnapshot {
    data class Idle(val reason: String) : HostSnapshot
    data class Playing(
        val nowPlaying: NowPlaying,
        val queue: List<QueueEntry>,
        val activeQueueId: Long?,
        val art: Bitmap?,
    ) : HostSnapshot
}
