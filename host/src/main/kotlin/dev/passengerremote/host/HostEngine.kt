package dev.passengerremote.host

import android.content.Context
import android.util.Log
import dev.passengerremote.host.control.CommandExecutor
import dev.passengerremote.host.control.DestinationSender
import dev.passengerremote.host.control.VoiceTrigger
import dev.passengerremote.host.control.VolumeController
import dev.passengerremote.host.access.CapabilityReporter
import dev.passengerremote.host.access.ElevatedAccess
import dev.passengerremote.host.media.ArtworkEncoder
import dev.passengerremote.host.media.HostSnapshot
import dev.passengerremote.host.media.SessionRepository
import dev.passengerremote.protocol.Artwork
import dev.passengerremote.protocol.CapabilityReport
import dev.passengerremote.protocol.Command
import dev.passengerremote.protocol.Hello
import dev.passengerremote.protocol.HostIdle
import dev.passengerremote.protocol.Message
import dev.passengerremote.protocol.Notice
import dev.passengerremote.protocol.NoticeLevel
import dev.passengerremote.protocol.PlaybackStatus
import dev.passengerremote.protocol.PolicyUpdate
import dev.passengerremote.protocol.QueueUpdate
import dev.passengerremote.protocol.RemotePolicy
import dev.passengerremote.protocol.RemoteRole
import dev.passengerremote.protocol.Suggestion
import dev.passengerremote.protocol.SuggestionKind
import dev.passengerremote.protocol.SuggestionResult
import dev.passengerremote.protocol.VolumeState
import dev.passengerremote.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** A remote currently attached to this host, with the policy it runs under. */
data class RemoteInfo(
    val id: String,
    val displayName: String,
    val role: RemoteRole,
    val policy: RemotePolicy,
    val isLoopback: Boolean,
)

data class PendingSuggestion(
    val suggestion: Suggestion,
    val fromRemoteId: String,
)

/**
 * Glue between the privileged side of the app and every attached remote.
 *
 * Attached remotes are uniform: a passenger's paired phone and the driver's own
 * loopback remote are both just a [Transport] plus a [RemotePolicy]. The engine
 * only ever branches on [Transport.isLoopback], and only to decide whether album
 * art needs compressing.
 */
class HostEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    val sessions = SessionRepository(context)
    val volume = VolumeController(context)

    /** Optional extra privilege. Null tier is the normal case, not a failure. */
    val elevated = ElevatedAccess(context)

    private val voice = VoiceTrigger(context, elevated)
    private val destinations = DestinationSender(context)
    private val executor = CommandExecutor(sessions, volume, voice)

    private val connections = mutableMapOf<String, Connection>()

    private val _remotes = MutableStateFlow<List<RemoteInfo>>(emptyList())
    val remotes: StateFlow<List<RemoteInfo>> = _remotes.asStateFlow()

    private val _suggestions = MutableStateFlow<List<PendingSuggestion>>(emptyList())
    val suggestions: StateFlow<List<PendingSuggestion>> = _suggestions.asStateFlow()

    private var broadcastJob: Job? = null
    private var lastSentTrackId: String? = null
    private var lastSentVolume: VolumeState? = null

    private inner class Connection(
        val transport: Transport,
        var info: RemoteInfo,
        val job: Job,
    )

    fun start() {
        elevated.start()
        sessions.start()
        if (broadcastJob == null) broadcastJob = scope.launch { broadcastLoop() }
    }

    fun stop() {
        broadcastJob?.cancel()
        broadcastJob = null
        synchronized(connections) {
            connections.values.forEach { it.job.cancel() }
            connections.clear()
        }
        _remotes.value = emptyList()
        sessions.stop()
        elevated.stop()
    }

    /**
     * What the app can actually do right now, measured rather than assumed.
     * Recomputed on demand because every input to it can change under us: the
     * playing app, the car, and whether Shizuku happens to be running.
     */
    fun capabilities(notificationAccess: Boolean): CapabilityReport = CapabilityReporter.report(
        tier = elevated.tier.value,
        notificationAccess = notificationAccess,
        snapshot = sessions.snapshot.value,
        volumeFixed = volume.isFixed,
    )

    /**
     * Attaches a remote. [policy] is the starting policy; for a loopback remote
     * the caller picks it from the role (OWNER when the driver is holding their
     * own phone, BACK_SEAT once they hand it over).
     */
    fun attach(
        transport: Transport,
        role: RemoteRole,
        displayName: String,
        policy: RemotePolicy,
    ): String {
        val id = transport.id + ":" + UUID.randomUUID().toString().take(8)
        val info = RemoteInfo(id, displayName, role, policy, transport.isLoopback)
        val job = scope.launch {
            transport.incoming.collect { message -> handle(id, message) }
        }
        synchronized(connections) { connections[id] = Connection(transport, info, job) }
        publishRemotes()
        scope.launch { sendInitialState(id) }
        return id
    }

    fun detach(id: String) {
        val connection = synchronized(connections) { connections.remove(id) } ?: return
        connection.job.cancel()
        executor.forget(id)
        publishRemotes()
    }

    fun setPolicy(id: String, policy: RemotePolicy) {
        val connection = synchronized(connections) { connections[id] } ?: return
        connection.info = connection.info.copy(policy = policy)
        publishRemotes()
        scope.launch { connection.transport.send(PolicyUpdate(policy)) }
    }

    fun policyOf(id: String): RemotePolicy? =
        synchronized(connections) { connections[id]?.info?.policy }

    // -- inbound -----------------------------------------------------------

    private suspend fun handle(remoteId: String, message: Message) {
        val connection = synchronized(connections) { connections[remoteId] } ?: return
        when (message) {
            is Command -> {
                val result = executor.execute(remoteId, message, connection.info.policy)
                connection.transport.send(result)
                // Volume and playback both change host state the remote cannot see
                // until we say so, so push immediately rather than waiting a tick.
                if (result.accepted) pushVolume(force = true)
            }

            is Hello -> {
                connection.info = connection.info.copy(
                    displayName = message.displayName.ifBlank { connection.info.displayName },
                    role = message.role,
                )
                publishRemotes()
                sendInitialState(remoteId)
            }

            is Suggestion -> handleSuggestion(connection, message)

            else -> Unit // hosts ignore host-bound message types
        }
    }

    private suspend fun handleSuggestion(connection: Connection, suggestion: Suggestion) {
        val policy = connection.info.policy
        val autoApprove = when (suggestion.kind) {
            SuggestionKind.DESTINATION -> policy.allowDestinations
            SuggestionKind.SONG -> false
        }
        if (autoApprove) {
            approve(suggestion, connection)
        } else {
            _suggestions.value = _suggestions.value + PendingSuggestion(suggestion, connection.info.id)
            connection.transport.send(
                Notice("Sent to the driver for approval", NoticeLevel.INFO),
            )
        }
    }

    /** Driver console calls this to act on a queued suggestion. */
    fun resolveSuggestion(id: String, approved: Boolean) {
        val pending = _suggestions.value.firstOrNull { it.suggestion.id == id } ?: return
        _suggestions.value = _suggestions.value.filterNot { it.suggestion.id == id }
        val connection = synchronized(connections) { connections[pending.fromRemoteId] }
        scope.launch {
            if (approved && connection != null) {
                approve(pending.suggestion, connection)
            } else {
                connection?.transport?.send(
                    SuggestionResult(id, approved = false, reason = "Driver declined"),
                )
            }
        }
    }

    private suspend fun approve(suggestion: Suggestion, connection: Connection) {
        val ok = when (suggestion.kind) {
            SuggestionKind.DESTINATION -> destinations.navigateTo(suggestion.query)
            // Queueing a song by name needs a per-app integration (Spotify App
            // Remote and friends); until one is wired up, approving a song
            // request only tells the driver what to play.
            SuggestionKind.SONG -> false
        }
        connection.transport.send(
            SuggestionResult(
                suggestion.id,
                approved = ok,
                reason = if (ok) null else "No app could handle that request",
            ),
        )
    }

    // -- outbound ----------------------------------------------------------

    private suspend fun broadcastLoop() {
        var lastStatus: PlaybackStatus? = null
        while (scope.isActive) {
            val hasRemotes = synchronized(connections) { connections.isNotEmpty() }
            if (hasRemotes) {
                val snapshot = sessions.snapshot.value
                pushSnapshot(snapshot)
                pushVolume(force = false)
                lastStatus = (snapshot as? HostSnapshot.Playing)?.nowPlaying?.status
            }
            // Position is extrapolated on the remote, so a slow sync is enough to
            // correct drift. Idling at the slow rate keeps this off the battery
            // when nobody is watching a moving progress bar.
            delay(if (lastStatus == PlaybackStatus.PLAYING) PLAYING_TICK_MS else IDLE_TICK_MS)
        }
    }

    private suspend fun pushSnapshot(snapshot: HostSnapshot) {
        when (snapshot) {
            is HostSnapshot.Idle -> {
                lastSentTrackId = null
                broadcast(HostIdle(snapshot.reason))
            }

            is HostSnapshot.Playing -> {
                broadcast(snapshot.nowPlaying)
                broadcast(QueueUpdate(snapshot.queue, snapshot.activeQueueId))
                if (snapshot.nowPlaying.trackId != lastSentTrackId) {
                    lastSentTrackId = snapshot.nowPlaying.trackId
                    pushArtwork(snapshot)
                }
            }
        }
    }

    private suspend fun pushArtwork(snapshot: HostSnapshot.Playing) {
        val bitmap = snapshot.art ?: return
        val trackId = snapshot.nowPlaying.trackId
        val targets = synchronized(connections) { connections.values.toList() }
        // Encode once, and only if some remote actually needs bytes.
        val encoded = if (targets.any { !it.transport.isLoopback }) {
            runCatching { ArtworkEncoder.encode(trackId, bitmap) }.getOrNull()
        } else {
            null
        }
        targets.forEach { connection ->
            val message = if (connection.transport.isLoopback) {
                Artwork(trackId, hash = "art:$trackId", bitmap = bitmap)
            } else {
                encoded?.let { Artwork(trackId, it.hash, it.mime, it.bytes) }
            } ?: return@forEach
            runCatching { connection.transport.send(message) }
                .onFailure { Log.w(TAG, "artwork send failed", it) }
        }
    }

    private suspend fun pushVolume(force: Boolean) {
        val state = volume.state()
        if (!force && state == lastSentVolume) return
        lastSentVolume = state
        broadcast(state)
    }

    private suspend fun sendInitialState(remoteId: String) {
        val connection = synchronized(connections) { connections[remoteId] } ?: return
        connection.transport.send(PolicyUpdate(connection.info.policy))
        connection.transport.send(volume.state())
        when (val snapshot = sessions.snapshot.value) {
            is HostSnapshot.Idle -> connection.transport.send(HostIdle(snapshot.reason))
            is HostSnapshot.Playing -> {
                connection.transport.send(snapshot.nowPlaying)
                connection.transport.send(QueueUpdate(snapshot.queue, snapshot.activeQueueId))
                snapshot.art?.let { art ->
                    val trackId = snapshot.nowPlaying.trackId
                    val message = if (connection.transport.isLoopback) {
                        Artwork(trackId, "art:$trackId", bitmap = art)
                    } else {
                        runCatching { ArtworkEncoder.encode(trackId, art) }.getOrNull()
                            ?.let { Artwork(trackId, it.hash, it.mime, it.bytes) }
                    }
                    message?.let { connection.transport.send(it) }
                }
            }
        }
    }

    private suspend fun broadcast(message: Message) {
        val targets = synchronized(connections) { connections.values.toList() }
        targets.forEach { connection ->
            runCatching { connection.transport.send(message) }
                .onFailure { Log.w(TAG, "send failed to ${connection.info.id}", it) }
        }
    }

    private fun publishRemotes() {
        _remotes.value = synchronized(connections) { connections.values.map { it.info } }
    }

    /** Convenience for the UI: current now-playing without going through a remote. */
    val snapshot: StateFlow<HostSnapshot> get() = sessions.snapshot

    private companion object {
        const val TAG = "HostEngine"
        const val PLAYING_TICK_MS = 2_000L
        const val IDLE_TICK_MS = 5_000L
    }
}
