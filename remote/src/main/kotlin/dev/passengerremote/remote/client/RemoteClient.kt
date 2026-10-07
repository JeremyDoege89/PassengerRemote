package dev.passengerremote.remote.client

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.passengerremote.protocol.Action
import dev.passengerremote.protocol.Artwork
import dev.passengerremote.protocol.Command
import dev.passengerremote.protocol.CommandResult
import dev.passengerremote.protocol.Hello
import dev.passengerremote.protocol.HostIdle
import dev.passengerremote.protocol.NowPlaying
import dev.passengerremote.protocol.Notice
import dev.passengerremote.protocol.PlaybackStatus
import dev.passengerremote.protocol.PolicyUpdate
import dev.passengerremote.protocol.QueueEntry
import dev.passengerremote.protocol.QueueUpdate
import dev.passengerremote.protocol.RemotePolicy
import dev.passengerremote.protocol.RemoteRole
import dev.passengerremote.protocol.Suggestion
import dev.passengerremote.protocol.SuggestionKind
import dev.passengerremote.protocol.SuggestionResult
import dev.passengerremote.protocol.VolumeState
import dev.passengerremote.transport.Transport
import dev.passengerremote.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The remote half of the app: consumes protocol messages and exposes UI state.
 *
 * Knows nothing about media sessions, audio, or which kind of link it is on. A
 * passenger's phone and the driver's own phone construct this identically, with
 * a different [Transport].
 */
class RemoteClient(
    private val transport: Transport,
    private val scope: CoroutineScope,
) {

    data class State(
        val connectionState: TransportState = TransportState.IDLE,
        val nowPlaying: NowPlaying? = null,
        val idleReason: String? = null,
        val artwork: ImageBitmap? = null,
        val artworkTrackId: String? = null,
        val queue: List<QueueEntry> = emptyList(),
        val activeQueueId: Long? = null,
        val volume: VolumeState? = null,
        val policy: RemotePolicy = RemotePolicy(),
        val notice: String? = null,
    ) {
        val isPlaying: Boolean get() = nowPlaying?.status == PlaybackStatus.PLAYING

        /**
         * Position, extrapolated from the last sync.
         *
         * The host only syncs every couple of seconds; a progress bar that moved
         * in 2-second jumps would look broken, so the remote advances it locally
         * against the same elapsedRealtime clock the host sampled.
         */
        fun estimatedPositionMs(now: Long = SystemClock.elapsedRealtime()): Long {
            val playing = nowPlaying ?: return 0L
            if (playing.status != PlaybackStatus.PLAYING) return playing.positionMs
            val elapsed = (now - playing.positionUpdatedAt).coerceAtLeast(0L)
            val estimated = playing.positionMs + (elapsed * playing.speed).toLong()
            return if (playing.durationMs > 0) estimated.coerceAtMost(playing.durationMs) else estimated
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(role: RemoteRole, displayName: String) {
        scope.launch {
            transport.state.collect { s -> _state.value = _state.value.copy(connectionState = s) }
        }
        scope.launch {
            transport.incoming.collect(::onMessage)
        }
        announce(role, displayName)
    }

    /**
     * Re-declares who is holding this remote, without re-subscribing.
     *
     * Needed because the role can change under a single long-lived client: the
     * driver hands the phone to the back seat while the remote is already open.
     */
    fun announce(role: RemoteRole, displayName: String) {
        scope.launch { transport.send(Hello(role, displayName)) }
    }

    fun send(action: Action, positionMs: Long? = null, volumeIndex: Int? = null, queueId: Long? = null) {
        scope.launch {
            transport.send(Command(action, positionMs, volumeIndex, queueId))
        }
    }

    fun suggest(kind: SuggestionKind, query: String, from: String) {
        scope.launch {
            transport.send(
                Suggestion(UUID.randomUUID().toString(), kind, query, from),
            )
        }
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    private fun onMessage(message: dev.passengerremote.protocol.Message) {
        val current = _state.value
        _state.value = when (message) {
            is NowPlaying -> current.copy(nowPlaying = message, idleReason = null)

            is HostIdle -> current.copy(
                nowPlaying = null,
                idleReason = message.reason,
                artwork = null,
                artworkTrackId = null,
                queue = emptyList(),
            )

            is Artwork -> current.copy(
                artwork = message.toImageBitmap() ?: current.artwork,
                artworkTrackId = message.trackId,
            )

            is QueueUpdate -> current.copy(queue = message.items, activeQueueId = message.activeQueueId)

            is VolumeState -> current.copy(volume = message)

            is PolicyUpdate -> current.copy(policy = message.policy)

            is Notice -> current.copy(notice = message.text)

            is CommandResult ->
                if (message.accepted) current else current.copy(notice = message.reason)

            is SuggestionResult -> current.copy(
                notice = if (message.approved) "Driver approved your request" else message.reason,
            )

            else -> current
        }
    }

    /**
     * Loopback hands over the Bitmap itself; a radio transport hands over WebP
     * bytes. Either way the UI just gets an ImageBitmap.
     */
    private fun Artwork.toImageBitmap(): ImageBitmap? {
        bitmap?.let { return it.asImageBitmap() }
        val payload = bytes ?: return null
        return runCatching {
            BitmapFactory.decodeByteArray(payload, 0, payload.size)?.asImageBitmap()
        }.getOrNull()
    }
}
