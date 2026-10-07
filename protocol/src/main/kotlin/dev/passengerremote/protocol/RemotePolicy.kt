package dev.passengerremote.protocol

/**
 * What a given remote is allowed to do. The host is the only thing that
 * enforces this; the remote receives a copy purely so it can grey out controls
 * instead of letting someone tap a button that will be silently refused.
 *
 * Policy applies identically to a paired passenger phone and to the driver's
 * own phone in HANDOFF mode -- that is the whole point of handing the phone to
 * the back seat with [BACK_SEAT] applied rather than trusting a kid with
 * [OWNER].
 */
data class RemotePolicy(
    val allowPlayback: Boolean = true,
    val allowSeek: Boolean = true,
    val allowVolume: Boolean = true,
    /** Hard ceiling on the volume index this remote may set. null = no cap. */
    val maxVolumeIndex: Int? = null,
    val allowQueueJump: Boolean = true,
    val allowDestinations: Boolean = false,
    val allowVoice: Boolean = false,
    /** Skip-spam guard. null = unlimited. */
    val maxSkipsPerMinute: Int? = null,
) {
    fun allows(action: Action): Boolean = when (action) {
        Action.PLAY, Action.PAUSE, Action.PLAY_PAUSE, Action.NEXT, Action.PREVIOUS -> allowPlayback
        Action.SEEK -> allowPlayback && allowSeek
        Action.SET_VOLUME, Action.VOLUME_UP, Action.VOLUME_DOWN -> allowVolume
        Action.SKIP_TO_QUEUE_ITEM -> allowPlayback && allowQueueJump
        Action.VOICE -> allowVoice
    }

    /** Clamps a requested volume index to this policy's ceiling. */
    fun clampVolume(index: Int, deviceMax: Int): Int {
        val ceiling = minOf(maxVolumeIndex ?: deviceMax, deviceMax)
        return index.coerceIn(0, ceiling)
    }

    companion object {
        /** The driver driving their own phone. No reason to restrict anything. */
        val OWNER = RemotePolicy(
            allowDestinations = true,
            allowVoice = true,
        )

        /** Default for a paired adult passenger. Driver still approves destinations. */
        val PASSENGER = RemotePolicy(
            allowDestinations = false,
            allowVoice = false,
            maxSkipsPerMinute = 20,
        )

        /**
         * Default for the phone once it has been handed to the back seat, and
         * the sane starting point for a kid's own device.
         */
        val BACK_SEAT = RemotePolicy(
            allowSeek = false,
            allowVolume = true,
            maxVolumeIndex = null, // set from the current device volume at handoff
            allowQueueJump = false,
            allowDestinations = false,
            allowVoice = false,
            maxSkipsPerMinute = 6,
        )
    }
}

fun RemotePolicy.Companion.forRole(role: RemoteRole): RemotePolicy = when (role) {
    RemoteRole.SELF -> OWNER
    RemoteRole.HANDOFF -> BACK_SEAT
    RemoteRole.PASSENGER -> PASSENGER
}
