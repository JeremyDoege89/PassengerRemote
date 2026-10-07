package dev.passengerremote.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.passengerremote.protocol.HandoffSettings
import kotlin.math.roundToInt

/**
 * What the driver sets before passing the phone back.
 *
 * Shown inline rather than behind a settings screen on purpose: these choices
 * are made in the moment, with a kid already reaching for the phone, not during
 * a configuration session.
 */
@Composable
fun HandoffSetup(
    settings: HandoffSettings,
    onSettingsChange: (HandoffSettings) -> Unit,
    onHandOver: () -> Unit,
    enabled: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What the back seat can do", fontWeight = FontWeight.SemiBold)

            Toggle(
                label = "Change the volume",
                checked = settings.allowVolume,
                onCheckedChange = { onSettingsChange(settings.copy(allowVolume = it)) },
            )

            if (settings.allowVolume) {
                Text(
                    "Louder by up to ${settings.volumeHeadroom} " +
                        if (settings.volumeHeadroom == 1) "step" else "steps",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = settings.volumeHeadroom.toFloat(),
                    onValueChange = {
                        onSettingsChange(settings.copy(volumeHeadroom = it.roundToInt()))
                    },
                    valueRange = 0f..HandoffSettings.MAX_VOLUME_HEADROOM.toFloat(),
                    steps = HandoffSettings.MAX_VOLUME_HEADROOM - 1,
                )
                Text(
                    "The cap is worked out when you hand the phone over, from whatever " +
                        "the volume is then.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Toggle(
                label = "Scrub through a track",
                checked = settings.allowSeek,
                onCheckedChange = { onSettingsChange(settings.copy(allowSeek = it)) },
            )

            Toggle(
                label = "Jump around the queue",
                checked = settings.allowQueueJump,
                onCheckedChange = { onSettingsChange(settings.copy(allowQueueJump = it)) },
            )

            Text(
                if (settings.maxSkipsPerMinute > 0) {
                    "At most ${settings.maxSkipsPerMinute} skips a minute"
                } else {
                    "Unlimited skips"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = settings.maxSkipsPerMinute.toFloat(),
                onValueChange = {
                    onSettingsChange(settings.copy(maxSkipsPerMinute = it.roundToInt()))
                },
                valueRange = 0f..HandoffSettings.MAX_SKIPS_PER_MINUTE.toFloat(),
            )

            Text(
                "Sending destinations and the voice button are always off once the " +
                    "phone leaves your hands.",
                style = MaterialTheme.typography.labelSmall,
            )

            Button(onClick = onHandOver, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("Hand it over")
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
