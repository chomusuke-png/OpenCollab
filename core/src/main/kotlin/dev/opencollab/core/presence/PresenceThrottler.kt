package dev.opencollab.core.presence

import dev.opencollab.core.util.Cancellable
import dev.opencollab.core.util.Scheduler

/**
 * Coalesces rapid caret/selection updates: the first update is sent immediately, later
 * ones at most once per [intervalMillis], always delivering the latest value (trailing edge).
 */
class PresenceThrottler<T : Any>(
    private val intervalMillis: Long,
    private val scheduler: Scheduler,
    private val send: (T) -> Unit,
) {
    private val lock = Any()
    private var lastSent = Long.MIN_VALUE / 2
    private var pending: T? = null
    private var scheduled: Cancellable? = null
    private var closed = false

    fun submit(value: T) {
        var toSend: T? = null
        synchronized(lock) {
            if (closed) return
            val now = scheduler.nowMillis()
            if (scheduled == null && now - lastSent >= intervalMillis) {
                lastSent = now
                toSend = value
            } else {
                pending = value
                if (scheduled == null) {
                    val delay = (lastSent + intervalMillis - now).coerceAtLeast(0)
                    scheduled = scheduler.schedule(delay) { flush() }
                }
            }
        }
        toSend?.let(send)
    }

    private fun flush() {
        val value: T
        synchronized(lock) {
            scheduled = null
            if (closed) return
            value = pending ?: return
            pending = null
            lastSent = scheduler.nowMillis()
        }
        send(value)
    }

    fun close() {
        synchronized(lock) {
            closed = true
            scheduled?.cancel()
            scheduled = null
            pending = null
        }
    }
}

/** Host-side token bucket that drops message floods from a misbehaving peer. */
class RateLimiter(private val perSecond: Int, private val scheduler: Scheduler) {
    private var tokens = perSecond.toDouble()
    private var last = scheduler.nowMillis()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = scheduler.nowMillis()
        tokens = minOf(perSecond.toDouble(), tokens + (now - last) * perSecond / 1000.0)
        last = now
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}
