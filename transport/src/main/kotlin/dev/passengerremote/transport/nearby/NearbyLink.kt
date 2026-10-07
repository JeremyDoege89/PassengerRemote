package dev.passengerremote.transport.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Nearby Connections plumbing, shared by both ends.
 *
 * P2P_STAR: one advertiser (the driver's phone) and many discoverers, which
 * matches a car exactly. Note the concurrency ceiling is a radio property, not
 * an app one -- Bluetooth tops out around four simultaneous connections.
 */
abstract class NearbyLink(context: Context) {

    protected val client = Nearby.getConnectionsClient(context.applicationContext)

    private val transports = linkedMapOf<String, NearbyTransport>()

    private val _connected = MutableStateFlow<List<NearbyTransport>>(emptyList())
    val connected: StateFlow<List<NearbyTransport>> = _connected.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Names seen but not yet connected, so a UI can show who is asking. */
    protected val pendingNames = mutableMapOf<String, String>()

    protected val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            transports[endpointId]?.onPayload(bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    protected val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingNames[endpointId] = info.endpointName
            onConnectionRequested(endpointId, info)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val transport = NearbyTransport(endpointId, client)
                    transports[endpointId] = transport
                    publish()
                    onConnected(endpointId, pendingNames[endpointId].orEmpty(), transport)
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                    _lastError.value = "The other device declined."

                else -> _lastError.value =
                    "Connection failed (${resolution.status.statusCode})."
            }
        }

        override fun onDisconnected(endpointId: String) {
            transports.remove(endpointId)?.markDisconnected()
            pendingNames.remove(endpointId)
            publish()
            onDisconnectedFrom(endpointId)
        }
    }

    private fun publish() {
        _connected.value = transports.values.toList()
    }

    /**
     * Accept or reject. The default accepts, because the app's own pairing and
     * policy layer is the security boundary -- Nearby's own authentication token
     * is surfaced separately for the driver to compare.
     */
    protected open fun onConnectionRequested(endpointId: String, info: ConnectionInfo) {
        client.acceptConnection(endpointId, payloadCallback)
    }

    protected open fun onConnected(endpointId: String, name: String, transport: NearbyTransport) = Unit

    protected open fun onDisconnectedFrom(endpointId: String) = Unit

    open fun stop() {
        transports.values.forEach { it.markDisconnected() }
        transports.clear()
        pendingNames.clear()
        publish()
        runCatching { client.stopAllEndpoints() }
    }

    fun disconnect(endpointId: String) {
        runCatching { client.disconnectFromEndpoint(endpointId) }
    }

    companion object {
        /** One hub, many spokes: the driver's phone and everyone else in the car. */
        val STRATEGY: Strategy = Strategy.P2P_STAR

        /** Must match on both ends; namespaced so other apps can't collide. */
        const val SERVICE_ID = "dev.passengerremote.link"

        internal const val TAG = "NearbyLink"
    }
}

/** The driver's phone. Advertises and accepts passengers. */
class NearbyHostLink(
    context: Context,
    private val advertisedName: String,
    private val onPassengerConnected: (endpointId: String, name: String, NearbyTransport) -> Unit,
    private val onPassengerGone: (endpointId: String) -> Unit,
) : NearbyLink(context) {

    fun startAdvertising() {
        client.startAdvertising(
            advertisedName,
            SERVICE_ID,
            lifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { Log.w(TAG, "advertising failed", it) }
    }

    override fun onConnected(endpointId: String, name: String, transport: NearbyTransport) {
        onPassengerConnected(endpointId, name, transport)
    }

    override fun onDisconnectedFrom(endpointId: String) = onPassengerGone(endpointId)

    override fun stop() {
        runCatching { client.stopAdvertising() }
        super.stop()
    }
}

/** A passenger's device. Discovers a driver and asks to join. */
class NearbyRemoteLink(
    context: Context,
    private val displayName: String,
    private val onHostConnected: (NearbyTransport) -> Unit,
) : NearbyLink(context) {

    private val _discovered = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    /** endpointId to advertised name, for a picker when more than one car answers. */
    val discovered: StateFlow<List<Pair<String, String>>> = _discovered.asStateFlow()

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            _discovered.value = _discovered.value + (endpointId to info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            _discovered.value = _discovered.value.filterNot { it.first == endpointId }
        }
    }

    fun startDiscovery() {
        client.startDiscovery(
            SERVICE_ID,
            discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { Log.w(TAG, "discovery failed", it) }
    }

    fun requestConnection(endpointId: String) {
        client.requestConnection(displayName, endpointId, lifecycleCallback)
            .addOnFailureListener { Log.w(TAG, "requestConnection failed", it) }
    }

    override fun onConnected(endpointId: String, name: String, transport: NearbyTransport) {
        runCatching { client.stopDiscovery() }
        onHostConnected(transport)
    }

    override fun stop() {
        runCatching { client.stopDiscovery() }
        _discovered.value = emptyList()
        super.stop()
    }
}
