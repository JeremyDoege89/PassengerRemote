package dev.passengerremote.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.passengerremote.host.control.VolumeController
import dev.passengerremote.host.service.RemoteHostService
import dev.passengerremote.protocol.RemotePolicy
import dev.passengerremote.protocol.RemoteRole
import dev.passengerremote.protocol.SuggestionKind
import dev.passengerremote.remote.client.RemoteClient
import dev.passengerremote.remote.ui.RemoteScreen
import dev.passengerremote.remote.ui.RemoteTheme
import dev.passengerremote.transport.LoopbackLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The remote, running on the phone that is also the host.
 *
 * Two modes, one screen:
 *  - SELF: the driver using their own phone as a big-button remote, full rights.
 *  - HANDOFF: the phone passed to the back seat. Same UI under the restricted
 *    policy, with the activity pinned to the screen so the rest of the phone is
 *    out of reach.
 *
 * The link is [LoopbackLink] -- the host service and this activity are in one
 * process, so nothing is paired, serialised or radioed. The host still enforces
 * policy on every command, which is what makes HANDOFF mean something.
 */
class SelfRemoteActivity : ComponentActivity() {

    /**
     * Read from the *current* intent, not the launching one.
     *
     * This activity is singleTask, so tapping "hand it over" while the remote is
     * already open re-delivers through onNewIntent rather than creating a new
     * activity. Reading `intent` once in onCreate meant the restricted profile
     * silently never applied in exactly that case -- the phone would go to the
     * back seat still holding OWNER rights.
     */
    private val handoffRequested = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handoffRequested.value = intent.getBooleanExtra(EXTRA_HANDOFF, false)

        // A remote nobody can see is useless; this screen is meant to sit face-up
        // in a cupholder or someone's hands.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val handoff by handoffRequested
            RemoteTheme {
                SelfRemoteContent(
                    handoff = handoff,
                    onExitHandoff = {
                        runCatching { stopLockTask() }
                        finish()
                    },
                    onStartPinning = { runCatching { startLockTask() } },
                    onFinish = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handoffRequested.value = intent.getBooleanExtra(EXTRA_HANDOFF, false)
    }

    companion object {
        const val EXTRA_HANDOFF = "handoff"
    }
}

@Composable
private fun SelfRemoteContent(
    handoff: Boolean,
    onExitHandoff: () -> Unit,
    onStartPinning: () -> Unit,
    onFinish: () -> Unit,
) {
    val service by rememberHostService()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val handoffSettings = remember { HandoffPreferences(context).load() }
    val link = remember { LoopbackLink.shared() }
    val client = remember { RemoteClient(link.remoteEnd, scope) }
    // Tracks which role we last announced, so a change of role while the remote
    // is already open re-announces instead of being missed.
    var announcedRole by remember { mutableStateOf<RemoteRole?>(null) }

    val role = if (handoff) RemoteRole.HANDOFF else RemoteRole.SELF

    DisposableEffect(service, handoff) {
        val host = service
        if (host != null) {
            val policy = if (handoff) {
                // Built here rather than stored, so the volume cap is relative to
                // however loud it is right now -- a remembered index would mean
                // nothing the next time the car is louder or quieter.
                handoffSettings.toPolicy(
                    currentVolumeIndex = host.engine.volume.state().index,
                    deviceMax = host.engine.volume.max,
                )
            } else {
                RemotePolicy.OWNER
            }
            host.attachSelfRemote(role, policy)
            val name = if (handoff) "Back seat" else "This phone"
            when (announcedRole) {
                null -> client.start(role, name)
                role -> Unit
                else -> client.announce(role, name)
            }
            announcedRole = role
        }
        onDispose {
            // Deliberately not detaching: the attachment is cheap and surviving a
            // rotation or a trip to the recents screen is worth more than tidiness.
        }
    }

    // Pinning has to happen after the window is up, or the system ignores it.
    LaunchedEffect(handoff, service) {
        if (handoff && service != null) {
            delay(250)
            onStartPinning()
        }
    }

    // In handoff mode Back must not walk out of the remote.
    BackHandler(enabled = handoff) { }

    val state by client.state.collectAsStateWithLifecycle()

    RemoteScreen(
        state = state,
        onAction = { action, positionMs, volumeIndex, queueId ->
            client.send(action, positionMs, volumeIndex, queueId)
        },
        onSuggestDestination = { query ->
            client.suggest(SuggestionKind.DESTINATION, query, from = "This phone")
        },
        volumeDisclaimer = VolumeController.FIXED_VOLUME_DISCLAIMER,
        header = {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (handoff) "Back seat remote" else "Remote",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (handoff) {
                    HoldToExitButton(onExitHandoff)
                } else {
                    TextButton(onClick = onFinish) { Text("Done") }
                }
            }
        },
    )
}

/**
 * Exiting handoff needs to be deliberate. The real barrier is Android's screen
 * pinning (and the system's "ask for PIN before unpinning" setting); this just
 * stops an idle tap from ending it.
 */
@Composable
private fun HoldToExitButton(onExit: () -> Unit) {
    var holding by remember { mutableStateOf(false) }

    Text(
        text = if (holding) "Keep holding\u2026" else "Give it back",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .padding(12.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        holding = true
                        // tryAwaitRelease returns as soon as the finger lifts, so a
                        // timeout means they actually held it down long enough.
                        val released = withTimeoutOrNull(HOLD_MS) { tryAwaitRelease() }
                        holding = false
                        if (released == null) onExit()
                    },
                )
            },
    )
}

private const val HOLD_MS = 1_500L
