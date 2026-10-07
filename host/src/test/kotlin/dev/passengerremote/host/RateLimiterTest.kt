package dev.passengerremote.host

import dev.passengerremote.host.control.RateLimiter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test
    fun `a null limit never blocks`() {
        val limiter = RateLimiter()
        repeat(100) { assertTrue(limiter.tryAcquire(null, now = it.toLong())) }
    }

    @Test
    fun `blocks once the limit is reached inside the window`() {
        val limiter = RateLimiter(windowMs = 60_000L)
        repeat(6) { assertTrue(limiter.tryAcquire(6, now = 1_000L + it)) }
        assertFalse(limiter.tryAcquire(6, now = 1_010L))
    }

    @Test
    fun `allows again once the window slides past the old events`() {
        val limiter = RateLimiter(windowMs = 60_000L)
        repeat(6) { assertTrue(limiter.tryAcquire(6, now = 1_000L)) }
        assertFalse(limiter.tryAcquire(6, now = 2_000L))
        assertTrue(limiter.tryAcquire(6, now = 1_000L + 60_001L))
    }
}
