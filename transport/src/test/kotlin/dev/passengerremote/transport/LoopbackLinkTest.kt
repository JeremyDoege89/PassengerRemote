package dev.passengerremote.transport

import dev.passengerremote.protocol.Action
import dev.passengerremote.protocol.Command
import dev.passengerremote.protocol.HostIdle
import dev.passengerremote.protocol.Message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoopbackLinkTest {

    @Test
    fun `host messages reach the remote end`() = runTest {
        val link = LoopbackLink.create()
        val received = async { link.remoteEnd.incoming.first() }
        yield()
        link.hostEnd.send(HostIdle("nothing playing"))
        assertEquals(HostIdle("nothing playing"), received.await())
    }

    @Test
    fun `remote commands reach the host end`() = runTest {
        val link = LoopbackLink.create()
        val received = async { link.hostEnd.incoming.first() }
        yield()
        link.remoteEnd.send(Command(Action.NEXT))
        assertEquals(Command(Action.NEXT), received.await())
    }

    @Test
    fun `host state replays for a remote that subscribes late`() = runTest {
        val link = LoopbackLink.create()
        link.hostEnd.send(HostIdle("sent before anyone was listening"))
        val replayed = link.remoteEnd.incoming.first()
        assertEquals(HostIdle("sent before anyone was listening"), replayed)
    }

    @Test
    fun `commands are never replayed to a host that resubscribes`() = runTest {
        val link = LoopbackLink.create()
        link.remoteEnd.send(Command(Action.NEXT))

        val collected = mutableListOf<Message>()
        val job = async {
            link.hostEnd.incoming.take(1).toList(collected)
        }
        yield()
        // Only the command sent *after* resubscribing should arrive; a replayed
        // NEXT here would mean the host skips a track every time it rebinds.
        link.remoteEnd.send(Command(Action.PLAY))
        job.await()
        assertEquals(listOf(Command(Action.PLAY)), collected)
    }

    @Test
    fun `resetReplay clears stale now-playing state`() = runTest {
        val link = LoopbackLink.create()
        link.hostEnd.send(HostIdle("stale"))
        link.resetReplay()
        val received = async { link.remoteEnd.incoming.first() }
        yield()
        link.hostEnd.send(HostIdle("fresh"))
        assertEquals(HostIdle("fresh"), received.await())
    }

    @Test
    fun `both ends report themselves as loopback`() {
        val link = LoopbackLink.create()
        assertTrue(link.hostEnd.isLoopback)
        assertTrue(link.remoteEnd.isLoopback)
    }
}
