package dev.passengerremote.app

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.passengerremote.host.access.ElevatedAccess
import dev.passengerremote.protocol.AccessTier
import dev.passengerremote.protocol.CapabilityReport
import dev.passengerremote.protocol.FeatureId
import dev.passengerremote.protocol.FeatureLevel
import dev.passengerremote.protocol.FeatureStatus

private fun label(id: FeatureId): String = when (id) {
    FeatureId.MEDIA_TRANSPORT -> "Play, pause, skip"
    FeatureId.NOW_PLAYING -> "What's playing"
    FeatureId.ARTWORK -> "Album art"
    FeatureId.SEEK -> "Scrub through a track"
    FeatureId.QUEUE -> "See and jump the queue"
    FeatureId.VOLUME -> "Volume"
    FeatureId.DESTINATION -> "Send a destination"
    FeatureId.VOICE -> "Voice button"
    FeatureId.HEAD_UNIT_APP_SWITCH -> "Switch apps on the car screen"
}

private fun mark(level: FeatureLevel): String = when (level) {
    FeatureLevel.WORKS -> "●"
    FeatureLevel.LIMITED -> "◐"
    FeatureLevel.BLOCKED -> "○"
}

private fun tint(level: FeatureLevel): Color = when (level) {
    FeatureLevel.WORKS -> Color(0xFF7FD1FF)
    FeatureLevel.LIMITED -> Color(0xFFE0C173)
    FeatureLevel.BLOCKED -> Color(0xFF9AA3A8)
}

/**
 * The honest inventory.
 *
 * Every feature states what it does *on this phone, in this car, with this
 * player* -- and the ones extra privilege would not fix say nothing about
 * Shizuku at all, which is the part that keeps the offer from reading as a
 * general unlock.
 */
@Composable
fun CapabilityCard(
    report: CapabilityReport,
    shizukuState: ElevatedAccess.ShizukuState,
    verification: String?,
    onRequestShizuku: () -> Unit,
    onVerify: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val improvable = report.improvable()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("What works in your car", fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Details")
                }
            }

            val works = report.features.count { it.level == FeatureLevel.WORKS }
            Text(
                "$works of ${report.features.size} features working" +
                    when (report.tier) {
                        AccessTier.ROOTLESS -> " with no special setup."
                        AccessTier.SHELL -> ", with Shizuku enabled."
                        AccessTier.ROOT -> ", with root access."
                    },
                style = MaterialTheme.typography.bodySmall,
            )

            if (expanded) {
                report.features.forEach { feature -> FeatureRow(feature) }
            }

            if (improvable.isNotEmpty() || shizukuState != ElevatedAccess.ShizukuState.NOT_INSTALLED) {
                ShizukuOffer(
                    improvable = improvable,
                    state = shizukuState,
                    verification = verification,
                    onRequestShizuku = onRequestShizuku,
                    onVerify = onVerify,
                )
            }
        }
    }
}

@Composable
private fun FeatureRow(feature: FeatureStatus) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(mark(feature.level), color = tint(feature.level))
            Text(label(feature.id), style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            feature.detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 22.dp),
        )
    }
}

@Composable
private fun ShizukuOffer(
    improvable: List<FeatureStatus>,
    state: ElevatedAccess.ShizukuState,
    verification: String?,
    onRequestShizuku: () -> Unit,
    onVerify: () -> Unit,
) {
    val context = LocalContext.current

    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Optional: make more of it work", fontWeight = FontWeight.SemiBold)
        improvable.forEach { feature ->
            Text(
                "• ${label(feature.id)} — ${feature.improvementDetail.orEmpty()}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            "Shizuku grants apps the same level of access a USB debugging session " +
                "has. It's a separate app you install and start yourself, and nothing " +
                "here stops working if you don't.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (state) {
            ElevatedAccess.ShizukuState.NOT_INSTALLED -> {
                Text(
                    "Shizuku isn't installed. Install it, then come back here.",
                    style = MaterialTheme.typography.labelSmall,
                )
                TextButton(onClick = { openShizukuListing(context) }) { Text("Get Shizuku") }
            }

            ElevatedAccess.ShizukuState.NOT_RUNNING -> Text(
                "Shizuku is installed but not running. Open it and start the service " +
                    "— it stops every time the phone reboots, which is a Shizuku " +
                    "limitation, not this app's.",
                style = MaterialTheme.typography.labelSmall,
            )

            ElevatedAccess.ShizukuState.TOO_OLD -> Text(
                "This version of Shizuku is too old for this app to use. Update it.",
                style = MaterialTheme.typography.labelSmall,
            )

            ElevatedAccess.ShizukuState.NEEDS_PERMISSION ->
                Button(onClick = onRequestShizuku) { Text("Allow this app to use Shizuku") }

            ElevatedAccess.ShizukuState.READY -> Text(
                "Shizuku is ready.",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // A permission prompt that reports nothing afterwards is indistinguishable
        // from one that failed, so make the result checkable on demand.
        Button(onClick = onVerify, modifier = Modifier.fillMaxWidth()) {
            Text("Check elevated access")
        }
        verification?.let {
            Text(it, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun openShizukuListing(context: android.content.Context) {
    val market = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=moe.shizuku.privileged.api"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(market) }
        .recoverCatching { context.startActivity(web) }
}
