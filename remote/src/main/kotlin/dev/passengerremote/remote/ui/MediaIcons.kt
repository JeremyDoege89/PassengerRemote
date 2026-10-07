package dev.passengerremote.remote.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The handful of transport icons this app needs, built from Material path data.
 *
 * Pulling in material-icons-extended for four glyphs costs a large dependency
 * (and it has churned between Compose releases), so they are declared here.
 */
object MediaIcons {
    val Play: ImageVector by lazy { icon("Play", "M8 5v14l11-7z") }
    val Pause: ImageVector by lazy { icon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z") }
    val SkipNext: ImageVector by lazy { icon("SkipNext", "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z") }
    val SkipPrevious: ImageVector by lazy { icon("SkipPrevious", "M6 6h2v12H6zm3.5 6l8.5 6V6z") }
    val VolumeUp: ImageVector by lazy {
        icon(
            "VolumeUp",
            "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 " +
                "2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 " +
                "6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
        )
    }
    val VolumeMute: ImageVector by lazy {
        icon("VolumeMute", "M7 9v6h4l5 5V4l-5 5H7z")
    }
    val Mic: ImageVector by lazy {
        icon(
            "Mic",
            "M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 " +
                "3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 " +
                "6.72V21h2v-3.28c3.28-.49 6-3.31 6-6.72h-1.7z",
        )
    }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = SolidColor(Color.White),
            )
        }.build()
}
