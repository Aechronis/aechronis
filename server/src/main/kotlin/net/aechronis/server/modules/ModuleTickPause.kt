package net.aechronis.server.modules

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue

/**
 * Holds the global tick between entity updates while the lifecycle worker replaces modules.
 * Only explicit live lifecycle actions are serviced; ordinary scheduler tasks, player packets,
 * instances and entities wait until release. Disk I/O remains on the lifecycle worker.
 */
internal class ModuleTickPause(
    tickExecutor: Executor,
) : Executor,
    AutoCloseable {
    private val actions = LinkedBlockingQueue<Runnable>()
    private val entered = CompletableFuture<Void>()
    private val finished = CompletableFuture<Void>()
    private var running = true

    init {
        tickExecutor.execute {
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
        entered.join()
    }

    override fun execute(command: Runnable) {
        actions.add(command)
    }

    override fun close() {
        actions.add { running = false }
        finished.join()
    }
}
