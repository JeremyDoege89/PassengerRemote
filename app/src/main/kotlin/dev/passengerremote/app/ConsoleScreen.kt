package dev.passengerremote.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import dev.passengerremote.host.control.VolumeController
import dev.passengerremote.host.media.HostSnapshot
import dev.passengerremote.host.media.NotificationAccess
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.passengerremote.host.access.ElevatedAccess
import kotlinx.coroutines.launch

/**
 * The driver's console: permission status, what the host currently sees, and the
 * two ways to open the remote on this very phone.
 */
@Composable
fun ConsoleScreen(onOpenSelfRemote: (handoff: Boolean) -> Unit) {
    val context = LocalContext.current
    val service by rememberHostService()
    var accessGranted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }

    val handoffPrefs = remember { HandoffPreferences(context) }
    var handoffSettings by remember { mutableStateOf(handoffPrefs.load()) }
    var showHandoffSetup by remember { mutableStateOf(false) }

    // Access is granted on a system screen, so re-check whenever we come back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessGranted = NotificationAccess.isGranted(context)
                service?.engine?.let { engine ->
                    if (accessGranted) engine.sessions.refresh()
                    // Shizuku may have been started, or stopped, while we were away.
                    engine.elevated.refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Passenger Remote", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        if (!accessGranted) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Media access needed", fontWeight = FontWeight.SemiBold)
                    Text(
                        "To read what's playing and send play/pause/skip, this app needs " +
                            "notification access. It never reads the contents of your " +
                            "notifications — that permission is simply the only way " +
                            "Android lets a normal app control another app's playback.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { context.startActivity(NotificationAccess.settingsIntent()) }) {
                        Text("Grant notification access")
                    }
                }
            }
        }

        val host = service
        val startError = if (host == null) null else host.startError.collectAsStateWithLifecycle().value
        if (startError != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The background host couldn't start", fontWeight = FontWeight.SemiBold)
                    Text(startError, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "The remote still works while this app is open, but it won't " +
                            "keep running in the background.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        val engine = service?.engine
        val snapshot = if (engine == null) null else engine.snapshot.collectAsStateWithLifecycle().value

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Host", fontWeight = FontWeight.SemiBold)
                when (snapshot) {
                    is HostSnapshot.Playing -> {
                        Text(snapshot.nowPlaying.title)
                        Text(
                            listOfNotNull(
                                snapshot.nowPlaying.artist,
                                snapshot.nowPlaying.sourceLabel,
                            ).joinToString(" — "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!snapshot.nowPlaying.caps.hasQueue) {
                            Text(
                                "This app doesn't publish a queue, so the remote will " +
                                    "show the current track only.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    is HostSnapshot.Idle -> Text(snapshot.reason, style = MaterialTheme.typography.bodySmall)
                    null -> Text("Starting…", style = MaterialTheme.typography.bodySmall)
                }
                service?.engine?.volume?.let { volume ->
                    if (volume.isFixed) {
                        Text(
                            VolumeController.FIXED_VOLUME_DISCLAIMER,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        if (engine != null) {
            val shizukuState by engine.elevated.shizukuState.collectAsStateWithLifecycle()
            val tier by engine.elevated.tier.collectAsStateWithLifecycle()
            // Keyed on everything the report measures, so it is never stale.
            val report = remember(accessGranted, snapshot, tier, shizukuState) {
                engine.capabilities(accessGranted)
            }
            val verification by engine.elevated.verification.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            CapabilityCard(
                report = report,
                shizukuState = shizukuState,
                verification = verification,
                onRequestShizuku = { engine.elevated.requestPermission() },
                onVerify = { scope.launch { engine.elevated.verify() } },
            )
        }

        Spacer(Modifier.height(4.dp))
        Text("Use the remote on this phone", fontWeight = FontWeight.SemiBold)
        Text(
            "The same remote the passenger app shows, running against this phone's " +
                "own player. Useful parked at the beach with the doors open, or any " +
                "time the big buttons beat digging through the music app.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { onOpenSelfRemote(false) },
            enabled = accessGranted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open remote")
        }

        Spacer(Modifier.height(4.dp))
        Text("Hand this phone to the back seat", fontWeight = FontWeight.SemiBold)
        Text(
            "Opens the same remote with the restricted profile and pins it to the " +
                "screen, so whoever you hand it to gets the music controls and nothing " +
                "else on your phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (showHandoffSetup) {
            HandoffSetup(
                settings = handoffSettings,
                onSettingsChange = {
                    handoffSettings = it
                    handoffPrefs.save(it)
                },
                onHandOver = {
                    showHandoffSetup = false
                    onOpenSelfRemote(true)
                },
                enabled = accessGranted,
            )
        } else {
            OutlinedButton(
                onClick = { showHandoffSetup = true },
                enabled = accessGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Set up and hand it over")
            }
        }
    }
}
