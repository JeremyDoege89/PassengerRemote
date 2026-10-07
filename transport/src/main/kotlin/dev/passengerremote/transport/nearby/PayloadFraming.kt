package dev.passengerremote.transport.nearby

import java.nio.ByteBuffer

/**
 * Splits encoded messages across multiple Nearby BYTES payloads.
 *
 * Nearby caps a BYTES payload at 32 KB. Album art is downscaled and WebP'd to
 * roughly 10-30 KB, which base64 inflates by a third -- so the single message
 * that matters most for the passenger experience is exactly the one that would
 * intermittently exceed the cap. Framing every message uniformly avoids a
 * size-dependent bug that would only ever show up on some album covers.
 *
 * Header: [int messageId][int chunkIndex][int chunkCount] then the chunk bytes.
 */
internal object PayloadFraming {

    const val HEADER_BYTES = 12

    /** Nearby's limit is 32768; leave room for the header and a safety margin. */
    const val MAX_CHUNK_BYTES = 30_000

    fun split(messageId: Int, data: ByteArray): List<ByteArray> {
        val count = ((data.size + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES).coerceAtLeast(1)
        return (0 until count).map { index ->
            val from = index * MAX_CHUNK_BYTES
            val to = minOf(from + MAX_CHUNK_BYTES, data.size)
            ByteBuffer.allocate(HEADER_BYTES + (to - from))
                .putInt(messageId)
                .putInt(index)
                .putInt(count)
                .put(data, from, to - from)
                .array()
        }
    }

    data class Chunk(val messageId: Int, val index: Int, val count: Int, val data: ByteArray) {
        override fun equals(other: Any?) = this === other ||
            (other is Chunk && other.messageId == messageId && other.index == index)

        override fun hashCode() = 31 * messageId + index
    }

    fun parse(payload: ByteArray): Chunk? {
        if (payload.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(payload)
        val messageId = buffer.int
        val index = buffer.int
        val count = buffer.int
        if (count <= 0 || index < 0 || index >= count) return null
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return Chunk(messageId, index, count, data)
    }
}

/**
 * Reassembles chunks back into whole messages.
 *
 * Bounded on purpose: a peer that sends the first chunk of a large message and
 * then drops would otherwise pin that memory until the connection closed.
 */
internal class PayloadAssembler(private val maxPending: Int = 4) {

    private val pending = LinkedHashMap<Int, Array<ByteArray?>>()

    fun accept(chunk: PayloadFraming.Chunk): ByteArray? {
        if (chunk.count == 1) return chunk.data

        val slots = pending.getOrPut(chunk.messageId) { arrayOfNulls(chunk.count) }
        if (slots.size != chunk.count) {
            pending.remove(chunk.messageId)
            return null
        }
        slots[chunk.index] = chunk.data

        while (pending.size > maxPending) {
            val oldest = pending.keys.first()
            if (oldest == chunk.messageId) break
            pending.remove(oldest)
        }

        if (slots.any { it == null }) return null
        pending.remove(chunk.messageId)
        val total = slots.sumOf { it!!.size }
        val out = ByteArray(total)
        var offset = 0
        slots.forEach { part ->
            part!!.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    fun clear() = pending.clear()
}
