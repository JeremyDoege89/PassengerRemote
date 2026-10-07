package dev.passengerremote.transport

import dev.passengerremote.protocol.Message
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [Transport] pair whose two ends are in the same process.
 *
 * This is what makes "run the remote on the driver's own phone" a transport
 * choice rather than a second code path. The host service publishes to
 * [hostEnd]; a remote UI hosted in this same app collects [remoteEnd]. Nothing
 * is serialised, no radio is touched, and no pairing happens -- but the host
 * still applies its policy layer, which is what lets the phone be handed to the
 * back seat under [dev.passengerremote.protocol.RemotePolicy.BACK_SEAT].
 */
class LoopbackLink private constructor() {

    // Host -> remote replays, because the remote UI is an Activity and will
    // resubscribe on every rotation or return-from-background. Without a replay
    // the screen would come back blank until the next metadata change, which for
    // a paused track could be never.
    private val toRemote = MutableSharedFlow<Message>(
        replay = REPLAY,
        extraBufferCapacity = BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Remote -> host does NOT replay. A replayed Command would be re-executed by
    // the host every time the service resubscribed -- skipping tracks on its own.
    private val toHost = MutableSharedFlow<Message>(
        replay = 0,
        extraBufferCapacity = BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val _state = MutableStateFlow(TransportState.CONNECTED)

    val hostEnd: Transport = End("loopback-host", outbound = toRemote, inbound = toHost)
    val remoteEnd: Transport = End("loopback-remote", outbound = toHost, inbound = toRemote)

    /** Drops replayed state so a re-opened remote never shows a stale track. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun resetReplay() {
        toRemote.resetReplayCache()
    }

    private inner class End(
        override val id: String,
        private val outbound: MutableSharedFlow<Message>,
        private val inbound: MutableSharedFlow<Message>,
    ) : Transport {
        override val isLoopback: Boolean = true
        override val state: StateFlow<TransportState> = _state.asStateFlow()
        override val incoming: Flow<Message> = inbound.asSharedFlow()
        override suspend fun send(message: Message) = outbound.emit(message)

        // Closing one end of a loopback link would close the app's own remote,
        // which is never what the caller wants; the link dies with the process.
        override fun close() = Unit
    }

    companion object {
        private const val REPLAY = 8
        private const val BUFFER = 64

        @Volatile
        private var shared: LoopbackLink? = null

        /**
         * The single link between this process's host service and its own remote
         * UI. Both live in one app, so one link is enough.
         */
        fun shared(): LoopbackLink =
            shared ?: synchronized(this) { shared ?: LoopbackLink().also { shared = it } }

        /** Independent link, for tests. */
        fun create(): LoopbackLink = LoopbackLink()
    }
}
