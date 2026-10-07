package dev.passengerremote.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.passengerremote.protocol.Action
import dev.passengerremote.protocol.PlaybackStatus
import dev.passengerremote.remote.client.RemoteClient
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The remote control.
 *
 * One screen, used unchanged in all three cases: a passenger's phone, the
 * driver's own phone, and the driver's phone after it has been handed to the
 * back seat. Differences between them arrive as [RemotePolicy] and are expressed
 * by disabling controls, never by swapping in a different UI.
 */
@Composable
fun RemoteScreen(
    state: RemoteClient.State,
    onAction: (Action, Long?, Int?, Long?) -> Unit,
    modifier: Modifier = Modifier,
    onSuggestDestination: (String) -> Unit = {},
    volumeDisclaimer: String? = null,
    header: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        header?.invoke()

        state.notice?.let { notice ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    notice,
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        ArtworkPanel(state, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        TrackText(state)
        Spacer(Modifier.height(12.dp))
        SeekRow(state, onAction)
        Spacer(Modifier.height(8.dp))
        TransportRow(state, onAction)
        Spacer(Modifier.height(8.dp))
        VolumeRow(state, onAction, volumeDisclaimer)
        ExtrasRow(state, onAction, onSuggestDestination)
        Spacer(Modifier.height(8.dp))
        QueuePanel(state, onAction, Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun ArtworkPanel(state: RemoteClient.State, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val art = state.artwork
        if (art != null) {
            androidx.compose.foundation.Image(
                bitmap = art,
                contentDescription = "Album art",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                state.idleReason ?: "No album art",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun TrackText(state: RemoteClient.State) {
    val playing = state.nowPlaying
    Text(
        text = playing?.title ?: "Nothing playing",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
    Text(
        text = listOfNotNull(playing?.artist, playing?.album).joinToString(" — ")
            .ifEmpty { playing?.sourceLabel ?: "" },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SeekRow(state: RemoteClient.State, onAction: (Action, Long?, Int?, Long?) -> Unit) {
    val playing = state.nowPlaying
    var positionMs by remember(playing?.trackId) { mutableLongStateOf(state.estimatedPositionMs()) }

    // Ticks locally between host syncs so the bar moves smoothly; the host's next
    // NowPlaying corrects any drift.
    LaunchedEffect(playing?.trackId, playing?.status, playing?.positionUpdatedAt) {
        while (true) {
            positionMs = state.estimatedPositionMs()
            if (playing?.status != PlaybackStatus.PLAYING) break
            delay(500)
        }
    }

    val duration = playing?.durationMs ?: 0L
    val canSeek = playing?.caps?.canSeek == true && state.policy.allowSeek && duration > 0

    if (canSeek) {
        Slider(
            value = positionMs.toFloat().coerceIn(0f, duration.toFloat()),
            onValueChange = { positionMs = it.toLong() },
            onValueChangeFinished = { onAction(Action.SEEK, positionMs, null, null) },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(
            progress = {
                if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
            },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(positionMs), style = MaterialTheme.typography.labelMedium)
        Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TransportRow(state: RemoteClient.State, onAction: (Action, Long?, Int?, Long?) -> Unit) {
    val enabled = state.policy.allowPlayback
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onAction(Action.PREVIOUS, null, null, null) },
            enabled = enabled && state.nowPlaying?.caps?.canSkipPrevious != false,
            modifier = Modifier.size(72.dp),
        ) {
            Icon(MediaIcons.SkipPrevious, "Previous", Modifier.size(44.dp))
        }
        Spacer(Modifier.size(12.dp))
        FilledIconButton(
            onClick = { onAction(Action.PLAY_PAUSE, null, null, null) },
            enabled = enabled,
            modifier = Modifier.size(96.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            Icon(
                if (state.isPlaying) MediaIcons.Pause else MediaIcons.Play,
                if (state.isPlaying) "Pause" else "Play",
                Modifier.size(52.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        IconButton(
            onClick = { onAction(Action.NEXT, null, null, null) },
            enabled = enabled && state.nowPlaying?.caps?.canSkipNext != false,
            modifier = Modifier.size(72.dp),
        ) {
            Icon(MediaIcons.SkipNext, "Next", Modifier.size(44.dp))
        }
    }
}

@Composable
private fun VolumeRow(
    state: RemoteClient.State,
    onAction: (Action, Long?, Int?, Long?) -> Unit,
    disclaimer: String?,
) {
    val volume = state.volume
    val fixed = volume?.fixed ?: true
    val allowed = state.policy.allowVolume && !fixed
    var showDisclaimer by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(MediaIcons.VolumeMute, null, Modifier.size(22.dp))
        Slider(
            value = (volume?.index ?: 0).toFloat(),
            onValueChange = { value ->
                onAction(Action.SET_VOLUME, null, value.roundToInt(), null)
            },
            valueRange = 0f..((volume?.max ?: 1).coerceAtLeast(1)).toFloat(),
            steps = ((volume?.max ?: 1) - 1).coerceAtLeast(0),
            enabled = allowed,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        Icon(MediaIcons.VolumeUp, null, Modifier.size(22.dp))
    }

    if (fixed && disclaimer != null) {
        TextButton(onClick = { showDisclaimer = !showDisclaimer }) {
            Text("Why is the volume slider off?", fontSize = 13.sp)
        }
        if (showDisclaimer) {
            Card(Modifier.fillMaxWidth()) {
                Text(disclaimer, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


/**
 * Destination and voice, both of which only the phone's owner gets.
 *
 * Renders nothing at all under the back-seat profile, so handing the phone over
 * doesn't leave a greyed-out "send destination" box inviting someone to poke at
 * it.
 */
@Composable
private fun ExtrasRow(
    state: RemoteClient.State,
    onAction: (Action, Long?, Int?, Long?) -> Unit,
    onSuggestDestination: (String) -> Unit,
) {
    val showDestination = state.policy.allowDestinations
    val showVoice = state.policy.allowVoice
    if (!showDestination && !showVoice) return

    var destination by remember { mutableStateOf("") }

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showDestination) {
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                placeholder = { Text("Navigate to\u2026") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        if (destination.isNotBlank()) {
                            onSuggestDestination(destination)
                            destination = ""
                        }
                    },
                ),
            )
            TextButton(
                onClick = {
                    if (destination.isNotBlank()) {
                        onSuggestDestination(destination)
                        destination = ""
                    }
                },
                enabled = destination.isNotBlank(),
            ) {
                Text("Go")
            }
        }
        if (showVoice) {
            IconButton(
                onClick = { onAction(Action.VOICE, null, null, null) },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(MediaIcons.Mic, "Voice", Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun QueuePanel(
    state: RemoteClient.State,
    onAction: (Action, Long?, Int?, Long?) -> Unit,
    modifier: Modifier = Modifier,
    onSuggestDestination: (String) -> Unit = {},
) {
    val hasQueue = state.nowPlaying?.caps?.hasQueue == true && state.queue.isNotEmpty()
    if (!hasQueue) {
        Text(
            text = state.nowPlaying?.sourceLabel
                ?.let { "$it doesn't share its queue" }
                ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(modifier.fillMaxWidth().heightIn(max = 240.dp)) {
        items(state.queue, key = { it.queueId }) { entry ->
            val active = entry.queueId == state.activeQueueId
            Button(
                onClick = { onAction(Action.SKIP_TO_QUEUE_ITEM, null, null, entry.queueId) },
                enabled = state.policy.allowQueueJump && !active,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    entry.artist?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
