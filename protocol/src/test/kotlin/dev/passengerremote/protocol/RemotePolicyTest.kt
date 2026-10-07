package dev.passengerremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePolicyTest {

    @Test
    fun `back seat profile blocks seeking and queue jumps but allows transport`() {
        val policy = RemotePolicy.BACK_SEAT
        assertTrue(policy.allows(Action.PLAY_PAUSE))
        assertTrue(policy.allows(Action.NEXT))
        assertFalse(policy.allows(Action.SEEK))
        assertFalse(policy.allows(Action.SKIP_TO_QUEUE_ITEM))
        assertFalse(policy.allows(Action.VOICE))
    }

    @Test
    fun `owner profile allows everything the host can do`() {
        val policy = RemotePolicy.OWNER
        Action.entries.forEach { action ->
            assertTrue("$action should be allowed for the phone's owner", policy.allows(action))
        }
    }

    @Test
    fun `volume ceiling caps a handed-over remote`() {
        val policy = RemotePolicy.BACK_SEAT.copy(maxVolumeIndex = 9)
        assertEquals(9, policy.clampVolume(15, deviceMax = 25))
        assertEquals(4, policy.clampVolume(4, deviceMax = 25))
        assertEquals(0, policy.clampVolume(-3, deviceMax = 25))
    }

    @Test
    fun `volume ceiling never exceeds the device maximum`() {
        val policy = RemotePolicy(maxVolumeIndex = 40)
        assertEquals(15, policy.clampVolume(40, deviceMax = 15))
    }

    @Test
    fun `roles map to the profile that matches who is holding the phone`() {
        assertEquals(RemotePolicy.OWNER, RemotePolicy.forRole(RemoteRole.SELF))
        assertEquals(RemotePolicy.BACK_SEAT, RemotePolicy.forRole(RemoteRole.HANDOFF))
        assertEquals(RemotePolicy.PASSENGER, RemotePolicy.forRole(RemoteRole.PASSENGER))
    }
}
