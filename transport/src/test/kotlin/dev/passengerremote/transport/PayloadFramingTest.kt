package dev.passengerremote.transport

import dev.passengerremote.transport.nearby.PayloadAssembler
import dev.passengerremote.transport.nearby.PayloadFraming
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PayloadFramingTest {

    private fun roundTrip(data: ByteArray, shuffle: Boolean = false): ByteArray? {
        val chunks = PayloadFraming.split(7, data).let { if (shuffle) it.reversed() else it }
        val assembler = PayloadAssembler()
        var result: ByteArray? = null
        chunks.forEach { raw ->
            val chunk = PayloadFraming.parse(raw)!!
            assembler.accept(chunk)?.let { result = it }
        }
        return result
    }

    @Test
    fun `a small message survives a single chunk`() {
        val data = """{"t":"cmd","action":"NEXT"}""".toByteArray()
        assertEquals(1, PayloadFraming.split(1, data).size)
        assertArrayEquals(data, roundTrip(data))
    }

    @Test
    fun `an album-art sized message survives being split`() {
        // Roughly a 40 KB base64 payload: the size that would silently break if
        // messages were handed to Nearby whole.
        val data = Random(1).nextBytes(40_000)
        assertTrue(PayloadFraming.split(1, data).size > 1)
        assertArrayEquals(data, roundTrip(data))
    }

    @Test
    fun `chunks never exceed the Nearby bytes limit`() {
        val data = Random(2).nextBytes(200_000)
        PayloadFraming.split(1, data).forEach { chunk ->
            assertTrue("chunk was ${chunk.size}", chunk.size <= 32_768)
        }
    }

    @Test
    fun `chunks arriving out of order still reassemble`() {
        val data = Random(3).nextBytes(95_000)
        assertArrayEquals(data, roundTrip(data, shuffle = true))
    }

    @Test
    fun `an empty message still produces one parseable chunk`() {
        val chunks = PayloadFraming.split(1, ByteArray(0))
        assertEquals(1, chunks.size)
        assertArrayEquals(ByteArray(0), roundTrip(ByteArray(0)))
    }

    @Test
    fun `a truncated payload is rejected rather than crashing`() {
        assertNull(PayloadFraming.parse(ByteArray(4)))
        assertNull(PayloadFraming.parse(ByteArray(0)))
    }

    @Test
    fun `interleaved messages do not corrupt each other`() {
        val first = Random(4).nextBytes(70_000)
        val second = Random(5).nextBytes(70_000)
        val a = PayloadFraming.split(1, first).map { PayloadFraming.parse(it)!! }
        val b = PayloadFraming.split(2, second).map { PayloadFraming.parse(it)!! }
        val assembler = PayloadAssembler()
        val done = mutableListOf<ByteArray>()
        a.zip(b).forEach { (x, y) ->
            assembler.accept(x)?.let(done::add)
            assembler.accept(y)?.let(done::add)
        }
        assertEquals(2, done.size)
        assertArrayEquals(first, done[0])
        assertArrayEquals(second, done[1])
    }

    @Test
    fun `an abandoned partial message is eventually evicted`() {
        val assembler = PayloadAssembler(maxPending = 2)
        // Five senders each drop after their first chunk. Without eviction this
        // would hold every partial message for the life of the connection.
        repeat(5) { id ->
            val chunk = PayloadFraming.parse(PayloadFraming.split(id, Random(id).nextBytes(70_000))[0])!!
            assertNull(assembler.accept(chunk))
        }
        // The first abandoned message is gone, so its late second chunk cannot
        // complete: a stale reassembly would be worse than a dropped message.
        val late = PayloadFraming.parse(PayloadFraming.split(0, Random(0).nextBytes(70_000))[1])!!
        assertNull(assembler.accept(late))
    }
}
