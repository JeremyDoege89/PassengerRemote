package dev.passengerremote.protocol

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hand-rolled JSON codec for the radio transports.
 *
 * Deliberately dependency-free: the message set is small and fixed, and keeping
 * :protocol free of kotlinx-serialization means it stays a plain library that
 * unit tests can exercise without a plugin. Swap for protobuf if payload size
 * ever matters -- the only call sites are inside the transports.
 *
 * Note that loopback never calls this. Self-remote passes the message objects
 * straight through, so this codec is not on the driver-phone path at all.
 */
object JsonCodec {

    fun encode(message: Message): String = toJson(message).toString()

    fun decode(text: String): Message? = runCatching { fromJson(JSONObject(text)) }.getOrNull()

    // -- encode ------------------------------------------------------------

    private fun toJson(m: Message): JSONObject = when (m) {
        is Hello -> obj("hello") {
            put("role", m.role.name)
            put("name", m.displayName)
            put("v", m.protocolVersion)
        }

        is Command -> obj("cmd") {
            put("action", m.action.name)
            m.positionMs?.let { put("posMs", it) }
            m.volumeIndex?.let { put("volIndex", it) }
            m.queueId?.let { put("qid", it) }
        }

        is Suggestion -> obj("sug") {
            put("id", m.id)
            put("kind", m.kind.name)
            put("query", m.query)
            put("from", m.from)
        }

        is NowPlaying -> obj("nowplaying") {
            put("trackId", m.trackId)
            put("title", m.title)
            putOpt("artist", m.artist)
            putOpt("album", m.album)
            put("durationMs", m.durationMs)
            put("positionMs", m.positionMs)
            put("posUpdatedAt", m.positionUpdatedAt)
            put("status", m.status.name)
            put("speed", m.speed.toDouble())
            putOpt("artHash", m.artHash)
            putOpt("pkg", m.sourcePackage)
            putOpt("source", m.sourceLabel)
            put(
                "caps",
                JSONObject()
                    .put("seek", m.caps.canSeek)
                    .put("next", m.caps.canSkipNext)
                    .put("prev", m.caps.canSkipPrevious)
                    .put("queue", m.caps.hasQueue),
            )
        }

        is HostIdle -> obj("idle") { put("reason", m.reason) }

        is Artwork -> obj("artwork") {
            put("trackId", m.trackId)
            put("hash", m.hash)
            putOpt("mime", m.mime)
            // A loopback-only Artwork carries a Bitmap and no bytes. Encoding one
            // would mean silently dropping the payload, so refuse loudly instead.
            val bytes = requireNotNull(m.bytes) {
                "Artwork must be encoded to bytes before it crosses a radio transport"
            }
            put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }

        is QueueUpdate -> obj("queue") {
            put(
                "items",
                JSONArray().apply {
                    m.items.forEach { e ->
                        put(
                            JSONObject()
                                .put("qid", e.queueId)
                                .put("title", e.title)
                                .putOpt("artist", e.artist),
                        )
                    }
                },
            )
            m.activeQueueId?.let { put("activeQid", it) }
        }

        is VolumeState -> obj("volume") {
            put("index", m.index)
            put("max", m.max)
            put("fixed", m.fixed)
        }

        is PolicyUpdate -> obj("policy") {
            put(
                "perms",
                JSONObject()
                    .put("playback", m.policy.allowPlayback)
                    .put("seek", m.policy.allowSeek)
                    .put("volume", m.policy.allowVolume)
                    .putOpt("maxVolIndex", m.policy.maxVolumeIndex)
                    .put("queueJump", m.policy.allowQueueJump)
                    .put("destinations", m.policy.allowDestinations)
                    .put("voice", m.policy.allowVoice)
                    .putOpt("maxSkipsPerMin", m.policy.maxSkipsPerMinute),
            )
        }

        is Notice -> obj("notice") {
            put("text", m.text)
            put("level", m.level.name)
        }

        is CommandResult -> obj("cmdResult") {
            put("action", m.action.name)
            put("accepted", m.accepted)
            putOpt("reason", m.reason)
        }

        is SuggestionResult -> obj("sugResult") {
            put("id", m.id)
            put("approved", m.approved)
            putOpt("reason", m.reason)
        }
    }

    private inline fun obj(type: String, body: JSONObject.() -> Unit): JSONObject =
        JSONObject().put("t", type).apply(body)

    // -- decode ------------------------------------------------------------

    private fun fromJson(j: JSONObject): Message? = when (j.getString("t")) {
        "hello" -> Hello(
            role = enumOf(j.getString("role"), RemoteRole.PASSENGER),
            displayName = j.optString("name"),
            protocolVersion = j.optInt("v", 0),
        )

        "cmd" -> Command(
            action = enumOf(j.getString("action"), Action.PLAY_PAUSE),
            positionMs = j.optLongOrNull("posMs"),
            volumeIndex = j.optIntOrNull("volIndex"),
            queueId = j.optLongOrNull("qid"),
        )

        "sug" -> Suggestion(
            id = j.getString("id"),
            kind = enumOf(j.getString("kind"), SuggestionKind.SONG),
            query = j.optString("query"),
            from = j.optString("from"),
        )

        "nowplaying" -> {
            val caps = j.optJSONObject("caps") ?: JSONObject()
            NowPlaying(
                trackId = j.getString("trackId"),
                title = j.optString("title"),
                artist = j.optStringOrNull("artist"),
                album = j.optStringOrNull("album"),
                durationMs = j.optLong("durationMs"),
                positionMs = j.optLong("positionMs"),
                positionUpdatedAt = j.optLong("posUpdatedAt"),
                status = enumOf(j.optString("status"), PlaybackStatus.NONE),
                speed = j.optDouble("speed", 1.0).toFloat(),
                artHash = j.optStringOrNull("artHash"),
                sourcePackage = j.optStringOrNull("pkg"),
                sourceLabel = j.optStringOrNull("source"),
                caps = Capabilities(
                    canSeek = caps.optBoolean("seek"),
                    canSkipNext = caps.optBoolean("next"),
                    canSkipPrevious = caps.optBoolean("prev"),
                    hasQueue = caps.optBoolean("queue"),
                ),
            )
        }

        "idle" -> HostIdle(j.optString("reason"))

        "artwork" -> Artwork(
            trackId = j.getString("trackId"),
            hash = j.getString("hash"),
            mime = j.optStringOrNull("mime"),
            bytes = Base64.decode(j.getString("b64"), Base64.NO_WRAP),
        )

        "queue" -> {
            val arr = j.optJSONArray("items") ?: JSONArray()
            QueueUpdate(
                items = (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    QueueEntry(
                        queueId = e.optLong("qid"),
                        title = e.optString("title"),
                        artist = e.optStringOrNull("artist"),
                    )
                },
                activeQueueId = j.optLongOrNull("activeQid"),
            )
        }

        "volume" -> VolumeState(
            index = j.optInt("index"),
            max = j.optInt("max"),
            fixed = j.optBoolean("fixed"),
        )

        "policy" -> {
            val p = j.optJSONObject("perms") ?: JSONObject()
            PolicyUpdate(
                RemotePolicy(
                    allowPlayback = p.optBoolean("playback", true),
                    allowSeek = p.optBoolean("seek", true),
                    allowVolume = p.optBoolean("volume", true),
                    maxVolumeIndex = p.optIntOrNull("maxVolIndex"),
                    allowQueueJump = p.optBoolean("queueJump", true),
                    allowDestinations = p.optBoolean("destinations", false),
                    allowVoice = p.optBoolean("voice", false),
                    maxSkipsPerMinute = p.optIntOrNull("maxSkipsPerMin"),
                ),
            )
        }

        "notice" -> Notice(j.optString("text"), enumOf(j.optString("level"), NoticeLevel.INFO))

        "cmdResult" -> CommandResult(
            action = enumOf(j.getString("action"), Action.PLAY_PAUSE),
            accepted = j.optBoolean("accepted"),
            reason = j.optStringOrNull("reason"),
        )

        "sugResult" -> SuggestionResult(
            id = j.getString("id"),
            approved = j.optBoolean("approved"),
            reason = j.optStringOrNull("reason"),
        )

        else -> null // forward compatibility: ignore message types we don't know
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
}
