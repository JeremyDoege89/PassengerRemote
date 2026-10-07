package dev.passengerremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffSettingsTest {

    @Test
    fun `volume ceiling is headroom above wherever the driver left it`() {
        val policy = HandoffSettings(volumeHeadroom = 2).toPolicy(currentVolumeIndex = 8, deviceMax = 25)
        assertEquals(10, policy.maxVolumeIndex)
    }

    @Test
    fun `ceiling never exceeds the device maximum`() {
        val policy = HandoffSettings(volumeHeadroom = 5).toPolicy(currentVolumeIndex = 23, deviceMax = 25)
        assertEquals(25, policy.maxVolumeIndex)
    }

    @Test
    fun `denying volume still pins the ceiling at the current level`() {
        val policy = HandoffSettings(allowVolume = false).toPolicy(currentVolumeIndex = 8, deviceMax = 25)
        assertFalse(policy.allowVolume)
        assertEquals(8, policy.maxVolumeIndex)
    }

    @Test
    fun `handing the phone over never grants destinations or voice`() {
        val settings = HandoffSettings(allowSeek = true, allowQueueJump = true)
        val policy = settings.toPolicy(currentVolumeIndex = 5, deviceMax = 15)
        assertFalse(policy.allowDestinations)
        assertFalse(policy.allowVoice)
        assertFalse(policy.allows(Action.VOICE))
    }

    @Test
    fun `a zero skip limit means unlimited rather than no skips at all`() {
        val policy = HandoffSettings(maxSkipsPerMinute = 0).toPolicy(currentVolumeIndex = 5, deviceMax = 15)
        assertNull(policy.maxSkipsPerMinute)
        assertTrue(policy.allows(Action.NEXT))
    }

    @Test
    fun `defaults match the documented back seat profile`() {
        val policy = HandoffSettings().toPolicy(currentVolumeIndex = 5, deviceMax = 15)
        assertFalse(policy.allowSeek)
        assertFalse(policy.allowQueueJump)
        assertEquals(6, policy.maxSkipsPerMinute)
        assertTrue(policy.allowPlayback)
    }
}
