package dev.passengerremote.host.control

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Sends a destination to navigation.
 *
 * This is the one thing that genuinely does change what the head unit shows:
 * firing a navigation intent while Android Auto is connected brings Maps to the
 * foreground on the car screen. There is no public API to foreground any *other*
 * app on the head unit, so this is the only screen-switching lever the app has.
 */
class DestinationSender(private val context: Context) {

    fun navigateTo(query: String): Boolean {
        val encoded = Uri.encode(query.trim())
        if (encoded.isEmpty()) return false
        val navigation = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded"))
            .setPackage(MAPS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch(navigation)) return true

        // Maps missing or disabled: fall back to the generic geo: query, which any
        // installed nav app can claim.
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(geo)
    }

    private fun launch(intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private companion object {
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}
