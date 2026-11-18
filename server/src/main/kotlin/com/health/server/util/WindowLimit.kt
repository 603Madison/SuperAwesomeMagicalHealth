package com.health.server.util

import java.util.concurrent.ConcurrentHashMap

/** At most [max] times per [windowMs] for each key (a person, say), kept in memory. */
class WindowLimit(private val max: Int, private val windowMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val seen = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** Whether [key] may go now; if so, it counts. */
    fun allow(key: String): Boolean {
        val now = clock()
        val times = seen.computeIfAbsent(key) { ArrayDeque() }
        synchronized(times) {
            while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
            if (times.size >= max) return false
            times.addLast(now)
            return true
        }
    }
}
