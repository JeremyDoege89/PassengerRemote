package dev.passengerremote.host.control

/**
 * Sliding-window counter used to cap how fast a restricted remote may skip.
 *
 * This is what stops a handed-over phone from turning into a skip button the
 * whole drive; it is enforced host-side so a modified remote cannot bypass it.
 */
class RateLimiter(private val windowMs: Long = 60_000L) {

    private val events = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(limit: Int?, now: Long): Boolean {
        if (limit == null) return true
        while (events.isNotEmpty() && now - events.first() > windowMs) events.removeFirst()
        if (events.size >= limit) return false
        events.addLast(now)
        return true
    }

    @Synchronized
    fun reset() = events.clear()
}
