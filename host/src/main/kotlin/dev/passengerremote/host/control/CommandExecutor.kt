package dev.passengerremote.host.control

import android.os.SystemClock
import dev.passengerremote.host.media.SessionRepository
import dev.passengerremote.protocol.Action
import dev.passengerremote.protocol.Command
import dev.passengerremote.protocol.CommandResult
import dev.passengerremote.protocol.RemotePolicy

/**
 * Applies policy, then executes.
 *
 * Every command from every remote goes through here, including the ones the
 * driver's own phone sends to itself over loopback. Keeping one enforcement
 * point is what makes handoff mode trustworthy: the restriction lives on the
 * host side of the link, not in the UI that the kid in the back seat is holding.
 */
class CommandExecutor(
    private val sessions: SessionRepository,
    private val volume: VolumeController,
    private val voice: VoiceTrigger,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    private val skipLimiters = mutableMapOf<String, RateLimiter>()

    suspend fun execute(remoteId: String, command: Command, policy: RemotePolicy): CommandResult {
        val action = command.action
        if (!policy.allows(action)) {
            return CommandResult(action, accepted = false, reason = "Not allowed for this remote")
        }
        if (action.isSkip() && !acquireSkip(remoteId, policy)) {
            return CommandResult(action, accepted = false, reason = "Too many skips, slow down")
        }

        return when (action) {
            Action.SET_VOLUME, Action.VOLUME_UP, Action.VOLUME_DOWN -> executeVolume(command, policy)
            Action.VOICE -> {
                val fired = voice.trigger()
                CommandResult(action, accepted = fired, reason = if (fired) null else "No assistant responded")
            }
            else -> executeTransport(command, policy)
        }
    }

    private fun executeVolume(command: Command, policy: RemotePolicy): CommandResult {
        val action = command.action
        if (volume.isFixed) {
            return CommandResult(action, accepted = false, reason = "This device reports fixed volume")
        }
        val deviceMax = volume.max
        when (action) {
            Action.SET_VOLUME -> {
                val requested = command.volumeIndex
                    ?: return CommandResult(action, false, "No volume index given")
                volume.set(policy.clampVolume(requested, deviceMax))
            }
            Action.VOLUME_UP -> volume.set(policy.clampVolume(volume.state().index + 1, deviceMax))
            Action.VOLUME_DOWN -> volume.set(policy.clampVolume(volume.state().index - 1, deviceMax))
            else -> Unit
        }
        return CommandResult(action, accepted = true)
    }

    private fun executeTransport(command: Command, policy: RemotePolicy): CommandResult {
        val action = command.action
        val controller = sessions.activeController()
            ?: return CommandResult(action, accepted = false, reason = "Nothing is playing")
        val transport = controller.transportControls

        when (action) {
            Action.PLAY -> transport.play()
            Action.PAUSE -> transport.pause()
            Action.PLAY_PAUSE -> {
                val playing = controller.playbackState?.state ==
                    android.media.session.PlaybackState.STATE_PLAYING
                if (playing) transport.pause() else transport.play()
            }
            Action.NEXT -> transport.skipToNext()
            Action.PREVIOUS -> transport.skipToPrevious()
            Action.SEEK -> {
                val position = command.positionMs
                    ?: return CommandResult(action, false, "No seek position given")
                transport.seekTo(position.coerceAtLeast(0L))
            }
            Action.SKIP_TO_QUEUE_ITEM -> {
                val queueId = command.queueId
                    ?: return CommandResult(action, false, "No queue item given")
                // skipToQueueItem is documented as undefined for ids the session
                // does not own, and several apps mis-report ids under shuffle, so
                // check the id against the live queue before sending it.
                val known = controller.queue.orEmpty().any { it.queueId == queueId }
                if (!known) {
                    return CommandResult(action, false, "That track is no longer in the queue")
                }
                transport.skipToQueueItem(queueId)
            }
            else -> return CommandResult(action, false, "Unhandled action")
        }
        return CommandResult(action, accepted = true)
    }

    private fun acquireSkip(remoteId: String, policy: RemotePolicy): Boolean {
        val limit = policy.maxSkipsPerMinute ?: return true
        val limiter = synchronized(skipLimiters) {
            skipLimiters.getOrPut(remoteId) { RateLimiter() }
        }
        return limiter.tryAcquire(limit, clock())
    }

    fun forget(remoteId: String) {
        synchronized(skipLimiters) { skipLimiters.remove(remoteId) }
    }

    private fun Action.isSkip() = this == Action.NEXT || this == Action.SKIP_TO_QUEUE_ITEM
}
