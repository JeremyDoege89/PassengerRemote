package dev.passengerremote.protocol

/**
 * What the driver decides *before* handing the phone over.
 *
 * Kept separate from [RemotePolicy] because a policy is per-connection state the
 * host hands out, whereas this is a stored preference: the driver sets it once,
 * and every subsequent handoff uses it. Turning it into a policy needs the live
 * volume, which is why that is an argument rather than a stored field -- capping
 * at a remembered index would be meaningless the next time the car is louder or
 * quieter.
 */
data class HandoffSettings(
    /** How many volume steps above the current level the back seat may go. */
    val volumeHeadroom: Int = DEFAULT_VOLUME_HEADROOM,
    val allowVolume: Boolean = true,
    val allowSeek: Boolean = false,
    val allowQueueJump: Boolean = false,
    val maxSkipsPerMinute: Int = DEFAULT_SKIPS_PER_MINUTE,
) {

    fun toPolicy(currentVolumeIndex: Int, deviceMax: Int): RemotePolicy = RemotePolicy(
        allowPlayback = true,
        allowSeek = allowSeek,
        allowVolume = allowVolume,
        maxVolumeIndex = if (allowVolume) {
            (currentVolumeIndex + volumeHeadroom).coerceIn(0, deviceMax)
        } else {
            // A remote that may not change the volume still gets a ceiling, so a
            // stale SET_VOLUME already in flight cannot land above where we are.
            currentVolumeIndex.coerceIn(0, deviceMax)
        },
        allowQueueJump = allowQueueJump,
        allowDestinations = false,
        allowVoice = false,
        maxSkipsPerMinute = maxSkipsPerMinute.takeIf { it > 0 },
    )

    companion object {
        const val DEFAULT_VOLUME_HEADROOM = 2
        const val DEFAULT_SKIPS_PER_MINUTE = 6
        const val MAX_VOLUME_HEADROOM = 10
        const val MAX_SKIPS_PER_MINUTE = 30
    }
}
