package com.nemap.android.core

/** Streams a Continue response as soon as its prefix can no longer be a duplicate. */
internal class ContinuationTail(
    private val previous: String,
    private val emit: (String) -> Unit
) {
    private val tail = StringBuilder()
    private var overlap: Int? = null
    private var delivered = 0

    fun append(delta: String) {
        if (delta.isEmpty()) return
        tail.append(delta)
        flush(force = false)
    }

    fun finish() = flush(force = true)

    fun fullText(): String {
        if (overlap == null) overlap = overlapLength(previous, tail.toString())
        val start = minOf(overlap ?: 0, tail.length)
        return previous + tail.substring(start)
    }

    private fun flush(force: Boolean) {
        if (overlap == null) {
            if (!force && mayStillOverlap(previous, tail.toString())) return
            overlap = overlapLength(previous, tail.toString())
            val start = minOf(overlap ?: 0, tail.length)
            if (tail.length > start) emit(tail.substring(start))
            delivered = tail.length
        } else if (tail.length > delivered) {
            emit(tail.substring(delivered))
            delivered = tail.length
        }
    }

    private fun mayStillOverlap(previous: String, prefix: String): Boolean {
        val max = minOf(previous.length, MAX_OVERLAP)
        if (prefix.isEmpty() || prefix.length >= max || max < MIN_OVERLAP) return false
        for (size in max downTo maxOf(prefix.length, MIN_OVERLAP)) {
            if (previous.regionMatches(previous.length - size, prefix, 0, prefix.length, ignoreCase = false)) return true
        }
        return false
    }

    private fun overlapLength(previous: String, next: String): Int {
        val max = minOf(previous.length, next.length, MAX_OVERLAP)
        for (size in max downTo MIN_OVERLAP) {
            if (previous.regionMatches(previous.length - size, next, 0, size, ignoreCase = false)) return size
        }
        return 0
    }

    private companion object {
        const val MIN_OVERLAP = 16
        const val MAX_OVERLAP = 4_000
    }
}
