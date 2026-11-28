package net.aechronis.server.modules

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the global tick between entity updates while the lifecycle worker replaces modules.
 * Only explicit live lifecycle actions are serviced; ordinary scheduler tasks, player packets,
 * instances and entities wait until release. Disk I/O remains on the lifecycle worker.
 * With an [enterTimeout], a tick that never reaches the pause fails construction instead of
 * blocking forever, and leaves gameplay running if it arrives later.
 */
internal class ModuleTickPause(
    tickExecutor: Executor,
    enterTimeout: Duration? = null,
) : Executor,
    AutoCloseable {
    private val actions = LinkedBlockingQueue<Runnable>()
    private val claimed = AtomicBoolean()
    private val entered = CompletableFuture<Void>()
    private val finished = CompletableFuture<Void>()
    private var running = true

    init {
        tickExecutor.execute {
            // The lifecycle worker gave up waiting, so this tick must not park.
            if (!claimed.compareAndSet(false, true)) return@execute
            entered.complete(null)
            var interrupted = false
            try {
                while (running) {
                    val action =
                        try {
                            actions.take()
                        } catch (_: InterruptedException) {
                            // An interrupt must not resume gameplay halfway through teardown.
                            interrupted = true
                            continue
                        }
                    action.run()
                }
                finished.complete(null)
            } catch (error: Throwable) {
                finished.completeExceptionally(error)
                throw error
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        if (enterTimeout == null) {
            entered.join()
        } else {
            try {
                entered.get(enterTimeout.toNanos(), TimeUnit.NANOSECONDS)
            } catch (error: TimeoutException) {
                if (claimed.compareAndSet(false, true)) {
                    throw IllegalStateException("The tick did not pause within ${enterTimeout.toSeconds()} seconds", error)
                }
                // The tick claimed the pause just as the wait ran out.
                entered.join()
            }
        }
    }

    override fun execute(command: Runnable) {
        actions.add(command)
    }

    override fun close() {
        actions.add { running = false }
        finished.join()
    }
}
