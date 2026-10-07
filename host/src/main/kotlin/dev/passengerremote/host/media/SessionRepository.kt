package dev.passengerremote.host.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.passengerremote.protocol.Capabilities
import dev.passengerremote.protocol.NowPlaying
import dev.passengerremote.protocol.PlaybackStatus
import dev.passengerremote.protocol.QueueEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whichever app currently owns the highest-priority media session and
 * turns its metadata into wire-ready state.
 *
 * getActiveSessions() returns controllers ordered by priority; index 0 is the
 * one the user would consider "what's playing", and is the one we drive.
 *
 * Note that this app must never publish a MediaSession of its own. If it did,
 * it could become index 0 and start controlling itself.
 */
class SessionRepository(private val context: Context) {

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _snapshot = MutableStateFlow<HostSnapshot>(HostSnapshot.Idle(REASON_NOT_STARTED))
    val snapshot: StateFlow<HostSnapshot> = _snapshot.asStateFlow()

    private var active: MediaController? = null
    private var started = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bind(controllers?.firstOrNull())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) = publish()
        override fun onSessionDestroyed() {
            bind(null)
            refresh()
        }
    }

    fun start() {
        if (started) return
        val component = MediaNotificationListener.componentName(context)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component, handler)
            started = true
            refresh()
        } catch (e: SecurityException) {
            // Thrown when notification-listener access has not been granted (or
            // was revoked while we were running). Not fatal -- the UI surfaces it.
            Log.w(TAG, "No notification listener access", e)
            _snapshot.value = HostSnapshot.Idle(REASON_NO_ACCESS)
        }
    }

    fun stop() {
        if (!started) return
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChanged) }
        bind(null)
        started = false
        _snapshot.value = HostSnapshot.Idle(REASON_NOT_STARTED)
    }

    /** Re-reads the session list. Call after granting access. */
    fun refresh() {
        val component = MediaNotificationListener.componentName(context)
        val controllers = try {
            manager.getActiveSessions(component)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification listener access", e)
            _snapshot.value = HostSnapshot.Idle(REASON_NO_ACCESS)
            return
        }
        bind(controllers.firstOrNull())
    }

    /** The controller commands are issued against. Null when nothing is playing. */
    fun activeController(): MediaController? = active

    private fun bind(controller: MediaController?) {
        if (controller?.sessionToken == active?.sessionToken) {
            publish()
            return
        }
        active?.unregisterCallback(controllerCallback)
        active = controller
        controller?.registerCallback(controllerCallback, handler)
        publish()
    }

    private fun publish() {
        val controller = active
        if (controller == null) {
            _snapshot.value = HostSnapshot.Idle(REASON_NO_SESSION)
            return
        }
        val metadata = controller.metadata
        val state = controller.playbackState
        if (metadata == null && state == null) {
            _snapshot.value = HostSnapshot.Idle(REASON_NO_SESSION)
            return
        }

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val art = metadata?.let { artworkOf(it) }
        val actions = state?.actions ?: 0L
        val queue = controller.queue.orEmpty().map { item ->
            QueueEntry(
                queueId = item.queueId,
                title = item.description.title?.toString().orEmpty(),
                artist = item.description.subtitle?.toString(),
            )
        }

        val trackId = trackIdOf(metadata, controller.packageName)
        val nowPlaying = NowPlaying(
            trackId = trackId,
            title = title.ifEmpty { "Unknown track" },
            artist = artist,
            album = album,
            durationMs = duration.coerceAtLeast(0L),
            positionMs = state?.position ?: 0L,
            // PlaybackState samples position against elapsedRealtime, so the remote
            // has to extrapolate against the same clock. Sending wall-clock here
            // would drift by however long the device has been awake.
            positionUpdatedAt = state?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            status = statusOf(state),
            speed = state?.playbackSpeed ?: 1f,
            artHash = art?.let { "art:$trackId" },
            sourcePackage = controller.packageName,
            sourceLabel = appLabel(controller.packageName),
            caps = Capabilities(
                canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
                canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                canSkipPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
                // Only true when the source app actually called setQueue(). Spotify
                // and YouTube Music do not, so the remote must handle an empty one.
                hasQueue = queue.isNotEmpty() &&
                    actions and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM != 0L,
            ),
        )

        _snapshot.value = HostSnapshot.Playing(
            nowPlaying = nowPlaying,
            queue = queue,
            activeQueueId = state?.activeQueueItemId?.takeIf { it >= 0 },
            art = art,
        )
    }

    private fun artworkOf(metadata: MediaMetadata) =
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    private fun trackIdOf(metadata: MediaMetadata?, pkg: String): String {
        metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.let { return it }
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        // Apps that omit MEDIA_ID still need a key that changes on track change,
        // so the remote knows when to drop its cached artwork.
        return "$pkg|$title|$artist|$duration"
    }

    private fun appLabel(pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { _: Throwable -> null }

    private fun statusOf(state: PlaybackState?): PlaybackStatus = when (state?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        -> PlaybackStatus.PLAYING
        PlaybackState.STATE_PAUSED -> PlaybackStatus.PAUSED
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> PlaybackStatus.BUFFERING
        PlaybackState.STATE_STOPPED -> PlaybackStatus.STOPPED
        null -> PlaybackStatus.NONE
        else -> PlaybackStatus.NONE
    }

    companion object {
        private const val TAG = "SessionRepository"
        const val REASON_NOT_STARTED = "Remote host is not running"
        const val REASON_NO_ACCESS = "Notification access has not been granted"
        const val REASON_NO_SESSION = "Nothing is playing"
    }
}
