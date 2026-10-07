package dev.passengerremote.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.passengerremote.remote.ui.RemoteTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The host runs as a foreground service, so on 13+ its notification needs
        // runtime consent or the service is invisible and killable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            RemoteTheme {
                ConsoleScreen(
                    onOpenSelfRemote = { handoff ->
                        startActivity(
                            Intent(this, SelfRemoteActivity::class.java)
                                .putExtra(SelfRemoteActivity.EXTRA_HANDOFF, handoff),
                        )
                    },
                )
            }
        }
    }
}
