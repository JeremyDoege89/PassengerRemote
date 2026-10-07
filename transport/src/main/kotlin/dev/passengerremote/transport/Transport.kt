package dev.passengerremote.transport

import dev.passengerremote.protocol.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class TransportState { IDLE, CONNECTING, CONNECTED, CLOSED }

/**
 * One end of a link between a host and a remote.
 *
 * Both halves of the app are written against this interface only. A passenger's
 * phone gets a radio-backed implementation; the driver's own phone gets
 * [LoopbackLink], which is the same interface over two in-process flows. No
 * code above this layer branches on which one it got.
 */
interface Transport {
    /** Stable id for this end of the link. Used as the key in the host's remote table. */
    val id: String

    /**
     * True when the peer lives in this process.
     *
     * Callers use this for two things and nothing else: skipping pairing UI, and
     * skipping album-art compression (a loopback peer can be handed the Bitmap).
     */
    val isLoopback: Boolean

    val state: StateFlow<TransportState>

    val incoming: Flow<Message>

    suspend fun send(message: Message)

    fun close()
}
