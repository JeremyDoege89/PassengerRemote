package dev.passengerremote.host.media

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

data class EncodedArtwork(val hash: String, val mime: String, val bytes: ByteArray) {
    override fun equals(other: Any?) = this === other || (other is EncodedArtwork && other.hash == hash)
    override fun hashCode() = hash.hashCode()
}

/**
 * Downscales and compresses album art for transports that cannot pass a Bitmap.
 *
 * Only radio transports pay this cost. A loopback remote is handed the original
 * Bitmap, so the driver's own phone never compresses art just to show it to
 * itself.
 */
object ArtworkEncoder {

    private const val MAX_EDGE = 384
    private const val QUALITY = 80

    /** Cached per track so N connected passengers encode once, not N times. */
    private var cacheKey: String? = null
    private var cached: EncodedArtwork? = null

    @Synchronized
    fun encode(trackId: String, source: Bitmap): EncodedArtwork {
        cached?.let { if (cacheKey == trackId) return it }
        val scaled = downscale(source)
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        scaled.compress(format, QUALITY, out)
        if (scaled !== source) scaled.recycle()
        val bytes = out.toByteArray()
        return EncodedArtwork(sha1(bytes), "image/webp", bytes)
            .also { cacheKey = trackId; cached = it }
    }

    private fun downscale(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_EDGE) return source
        val factor = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun sha1(bytes: ByteArray): String =
        "sha1:" + MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
