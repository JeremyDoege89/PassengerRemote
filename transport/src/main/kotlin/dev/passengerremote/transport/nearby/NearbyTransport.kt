package dev.passengerremote.transport.nearby

import android.util.Log
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import dev.passengerremote.protocol.JsonCodec
import dev.passengerremote.protocol.Message
import dev.passengerremote.transport.Transport
import dev.passengerremote.transport.TransportState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * One connected Nearby endpoint, behind the same [Transport] interface the
 * loopback link implements.
 *
 * Everything above this -- the host engine, the policy layer, the whole remote
 * UI -- is unchanged from the self-remote case. The only thing that differs is
 * [isLoopback], which makes album art take the compress-and-send path instead of
 * being handed over as a Bitmap.
 */
class NearbyTransport(
    override val id: String,
    private val client: ConnectionsClient,
) : Transport {

    override val isLoopback: Boolean = false

    private val _state = MutableStateFlow(TransportState.CONNECTED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Message>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: Flow<Message> = _incoming.asSharedFlow()

    private val assembler = PayloadAssembler()
    private val messageIds = AtomicInteger(0)

    override suspend fun send(message: Message) {
        val encoded = try {
            JsonCodec.encode(message).toByteArray()
        } catch (e: IllegalArgumentException) {
            // Thrown when an Artwork carrying only a Bitmap reaches a radio
            // transport. That is a caller bug, not a network failure.
            Log.e(TAG, "refusing to send unencodable message", e)
            return
        }
        PayloadFraming.split(messageIds.incrementAndGet(), encoded).forEach { chunk ->
            client.sendPayload(id, Payload.fromBytes(chunk))
        }
    }

    override fun close() {
        _state.value = TransportState.CLOSED
        assembler.clear()
        runCatching { client.disconnectFromEndpoint(id) }
    }

    /** Called by the link when Nearby delivers bytes for this endpoint. */
    internal fun onPayload(bytes: ByteArray) {
        val chunk = PayloadFraming.parse(bytes) ?: return
        val complete = assembler.accept(chunk) ?: return
        val message = JsonCodec.decode(String(complete)) ?: return
        _incoming.tryEmit(message)
    }

    internal fun markDisconnected() {
        _state.value = TransportState.CLOSED
        assembler.clear()
    }

    private companion object {
        const val TAG = "NearbyTransport"
    }
}
