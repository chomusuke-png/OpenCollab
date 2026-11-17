package dev.opencollab.core.util

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun daemonThreadFactory(name: String): ThreadFactory {
    val counter = AtomicInteger()
    return ThreadFactory { r -> Thread(r, "$name-${counter.incrementAndGet()}").apply { isDaemon = true } }
}

/** Runs tasks one at a time in submission order on a dedicated daemon thread. */
class SerialExecutor(name: String) : Executor, AutoCloseable {
    private val delegate: ExecutorService = Executors.newSingleThreadExecutor(daemonThreadFactory(name))

    override fun execute(command: Runnable) {
        if (!delegate.isShutdown) {
            try {
                delegate.execute(command)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // shutting down
            }
        }
    }

    override fun close() {
        delegate.shutdown()
    }
}

/** Minimal scheduling abstraction so throttling and reconnect backoff are testable. */
interface Scheduler {
    fun nowMillis(): Long
    fun schedule(delayMillis: Long, task: () -> Unit): Cancellable
}

fun interface Cancellable {
    fun cancel()
}

class SystemScheduler(name: String = "opencollab-timer") : Scheduler, AutoCloseable {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory(name))

    override fun nowMillis(): Long = System.nanoTime() / 1_000_000

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        if (executor.isShutdown) return Cancellable { }
        val future = try {
            executor.schedule({ task() }, delayMillis, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return Cancellable { }
        }
        return Cancellable { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
