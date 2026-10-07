package dev.passengerremote.protocol

import android.graphics.Bitmap

/** Bumped when the wire format changes incompatibly. Sent in [Hello]. */
const val PROTOCOL_VERSION = 1

/**
 * Everything the two halves of the app say to each other.
 *
 * The remote UI is built strictly against these messages and never touches
 * MediaController, AudioManager or any other privileged API. That is what lets
 * the exact same UI run on a passenger's phone (over a radio transport) and on
 * the driver's own phone (over [dev.passengerremote.transport.LoopbackLink]) with
 * no branching in the UI layer.
 */
sealed interface Message

// ---------------------------------------------------------------------------
// remote -> host
// ---------------------------------------------------------------------------

enum class RemoteRole {
    /** A second device in the car. Needs pairing and driver approval. */
    PASSENGER,

    /** The driver's own phone, running the remote against its own host. */
    SELF,

    /** The driver's own phone, handed to someone else with the UI pinned. */
    HANDOFF,
}

data class Hello(
    val role: RemoteRole,
    val displayName: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
) : Message

enum class Action {
    PLAY,
    PAUSE,
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    SEEK,
    SET_VOLUME,
    VOLUME_UP,
    VOLUME_DOWN,
    SKIP_TO_QUEUE_ITEM,
    VOICE,
}

data class Command(
    val action: Action,
    val positionMs: Long? = null,
    val volumeIndex: Int? = null,
    val queueId: Long? = null,
) : Message

enum class SuggestionKind { SONG, DESTINATION }

/**
 * A request the host does not execute on its own authority. On a paired
 * passenger device this lands in the driver console for approval; in SELF mode
 * the host auto-approves, because the person asking is the person who owns the
 * phone.
 */
data class Suggestion(
    val id: String,
    val kind: SuggestionKind,
    val query: String,
    val from: String,
) : Message

// ---------------------------------------------------------------------------
// host -> remote
// ---------------------------------------------------------------------------

enum class PlaybackStatus { PLAYING, PAUSED, BUFFERING, STOPPED, NONE }

data class Capabilities(
    val canSeek: Boolean = false,
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false,
    val hasQueue: Boolean = false,
)

data class NowPlaying(
    val trackId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val positionMs: Long,
    /** SystemClock.elapsedRealtime() when [positionMs] was sampled. */
    val positionUpdatedAt: Long,
    val status: PlaybackStatus,
    val speed: Float,
    val artHash: String?,
    val sourcePackage: String?,
    val sourceLabel: String?,
    val caps: Capabilities,
) : Message

/** No app is currently holding a media session the host can drive. */
data class HostIdle(val reason: String) : Message

/**
 * Album art for [trackId].
 *
 * Exactly one payload field is populated. Radio transports fill [bytes] with a
 * downscaled WebP; the loopback transport fills [bitmap] instead and skips the
 * encode entirely, since both ends share a heap. The UI just takes whichever
 * one it is handed.
 */
data class Artwork(
    val trackId: String,
    val hash: String,
    val mime: String? = null,
    val bytes: ByteArray? = null,
    val bitmap: Bitmap? = null,
) : Message {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Artwork && other.trackId == trackId && other.hash == hash)

    override fun hashCode(): Int = 31 * trackId.hashCode() + hash.hashCode()
}

data class QueueEntry(val queueId: Long, val title: String, val artist: String?)

data class QueueUpdate(val items: List<QueueEntry>, val activeQueueId: Long? = null) : Message

data class VolumeState(
    val index: Int,
    val max: Int,
    /** AudioManager.isVolumeFixed(): the car, not the phone, owns the volume. */
    val fixed: Boolean,
) : Message

data class PolicyUpdate(val policy: RemotePolicy) : Message

enum class NoticeLevel { INFO, WARNING, ERROR }

data class Notice(val text: String, val level: NoticeLevel = NoticeLevel.INFO) : Message

data class CommandResult(
    val action: Action,
    val accepted: Boolean,
    val reason: String? = null,
) : Message

data class SuggestionResult(
    val id: String,
    val approved: Boolean,
    val reason: String? = null,
) : Message
