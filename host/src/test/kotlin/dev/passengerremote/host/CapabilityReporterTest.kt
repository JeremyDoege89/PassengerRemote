package dev.passengerremote.host

import dev.passengerremote.host.access.CapabilityReporter
import dev.passengerremote.host.media.HostSnapshot
import dev.passengerremote.protocol.AccessTier
import dev.passengerremote.protocol.Capabilities
import dev.passengerremote.protocol.FeatureId
import dev.passengerremote.protocol.FeatureLevel
import dev.passengerremote.protocol.NowPlaying
import dev.passengerremote.protocol.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityReporterTest {

    private fun playing(caps: Capabilities) = HostSnapshot.Playing(
        nowPlaying = NowPlaying(
            trackId = "t",
            title = "Track",
            artist = "Artist",
            album = null,
            durationMs = 1000,
            positionMs = 0,
            positionUpdatedAt = 0,
            status = PlaybackStatus.PLAYING,
            speed = 1f,
            artHash = null,
            sourcePackage = "com.example",
            sourceLabel = "Example Player",
            caps = caps,
        ),
        queue = emptyList(),
        activeQueueId = null,
        art = null,
    )

    private fun report(
        tier: AccessTier = AccessTier.ROOTLESS,
        access: Boolean = true,
        caps: Capabilities = Capabilities(),
        volumeFixed: Boolean = false,
    ) = CapabilityReporter.report(tier, access, playing(caps), volumeFixed)

    @Test
    fun `a fixed-volume car is never presented as something Shizuku fixes`() {
        val volume = report(volumeFixed = true).statusOf(FeatureId.VOLUME)!!
        assertEquals(FeatureLevel.BLOCKED, volume.level)
        // The head unit owns its own amplifier. No privilege level changes that,
        // and offering one here would be selling a fix that does not exist.
        assertNull(volume.improvedBy)
    }

    @Test
    fun `a missing queue is never presented as something Shizuku fixes`() {
        val queue = report(caps = Capabilities(hasQueue = false)).statusOf(FeatureId.QUEUE)!!
        assertEquals(FeatureLevel.LIMITED, queue.level)
        assertNull(queue.improvedBy)
    }

    @Test
    fun `head unit app switching is never presented as something privilege fixes`() {
        val switch = report().statusOf(FeatureId.HEAD_UNIT_APP_SWITCH)!!
        assertEquals(FeatureLevel.BLOCKED, switch.level)
        assertNull(switch.improvedBy)
    }

    @Test
    fun `voice is the one feature elevated access actually improves`() {
        val rootless = report(tier = AccessTier.ROOTLESS)
        val voice = rootless.statusOf(FeatureId.VOICE)!!
        assertEquals(FeatureLevel.LIMITED, voice.level)
        assertEquals(AccessTier.SHELL, voice.improvedBy)
        assertEquals(listOf(FeatureId.VOICE), rootless.improvable().map { it.id })
    }

    @Test
    fun `nothing is left to improve once shell access is granted`() {
        val elevated = report(tier = AccessTier.SHELL)
        assertEquals(FeatureLevel.WORKS, elevated.statusOf(FeatureId.VOICE)!!.level)
        assertTrue(elevated.improvable().isEmpty())
    }

    @Test
    fun `root counts as at least shell so it does not re-offer Shizuku`() {
        assertTrue(AccessTier.ROOT.atLeast(AccessTier.SHELL))
        assertTrue(report(tier = AccessTier.ROOT).improvable().isEmpty())
    }

    @Test
    fun `without notification access the core features report blocked`() {
        val blocked = report(access = false)
        assertEquals(FeatureLevel.BLOCKED, blocked.statusOf(FeatureId.MEDIA_TRANSPORT)!!.level)
        assertEquals(FeatureLevel.BLOCKED, blocked.statusOf(FeatureId.NOW_PLAYING)!!.level)
        // ...and that is still not something extra privilege papers over.
        assertNull(blocked.statusOf(FeatureId.MEDIA_TRANSPORT)!!.improvedBy)
    }

    @Test
    fun `seek and queue follow what the playing app actually advertises`() {
        val rich = report(caps = Capabilities(canSeek = true, hasQueue = true))
        assertEquals(FeatureLevel.WORKS, rich.statusOf(FeatureId.SEEK)!!.level)
        assertEquals(FeatureLevel.WORKS, rich.statusOf(FeatureId.QUEUE)!!.level)
    }
}
