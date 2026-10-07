package dev.passengerremote.remote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1FF),
    onPrimary = Color(0xFF00344B),
    secondary = Color(0xFFB4CAD6),
    background = Color(0xFF0E1417),
    onBackground = Color(0xFFE1E3E5),
    surface = Color(0xFF0E1417),
    onSurface = Color(0xFFE1E3E5),
    surfaceVariant = Color(0xFF1B2429),
    onSurfaceVariant = Color(0xFFBFC8CD),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00658E),
    secondary = Color(0xFF4F616C),
)

@Composable
fun RemoteTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        // The Surface is not decoration. Outside one, Material3 leaves
        // LocalContentColor at black, so every plain Text on these dark screens
        // renders black-on-black -- which is exactly what the first build did.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // targetSdk 35+ is edge-to-edge whether we ask for it or not, so
            // content has to be inset out from under the status and nav bars.
            Box(Modifier.safeDrawingPadding()) {
                content()
            }
        }
    }
}
