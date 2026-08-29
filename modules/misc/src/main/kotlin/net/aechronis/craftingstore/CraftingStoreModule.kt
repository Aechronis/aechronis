package net.aechronis.craftingstore

import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import java.util.concurrent.atomic.AtomicReference

/** Native CraftingStore integration. The GUI is intentionally optional; donation
 * queue polling and server-command execution work without a menu implementation. */
object CraftingStoreModule {
    private val started = AtomicReference<RunningCraftingStore?>()

    val eventNode: EventNode<Event>
        get() = requireRunning().adapter.eventNode

    @Synchronized
    fun initialize(
        options: CraftingStoreOptions = CraftingStoreOptions(),
        attachEventNode: (EventNode<Event>) -> Unit = { node ->
            MinecraftServer.getGlobalEventHandler().addChild(node)
        },
    ) {
        started.get()?.let { running ->
            check(!running.cleanupStarted) { "CraftingStoreModule cleanup is incomplete" }
            check(running.fullyStarted) { "CraftingStoreModule initialization is incomplete" }
            return
        }
        val config = ConfigStore(options.dataDirectory)
        config.reload()
        val node = EventNode.all("craftingstore")
        val adapter = CraftingStorePluginAdapter(options, config, node)
        val running = RunningCraftingStore(adapter)
        started.set(running)
        try {
            adapter.prepareEventListeners()
            running.eventNodeCleanupPending = true
            attachEventNode(node)
            adapter.start()
            running.fullyStarted = true
        } catch (error: Throwable) {
            runCatching(::shutdown).exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    @Synchronized
    fun shutdown() {
        val running = started.get() ?: return
        running.cleanupStarted = true
        var failure: Throwable? = null

        fun cleanup(action: () -> Unit) {
            runCatching(action).onFailure { error ->
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }

        if (!running.adapterStopped) {
            cleanup {
                running.adapter.shutdown()
                running.adapterStopped = true
            }
        }
        if (running.eventNodeCleanupPending) {
            cleanup {
                MinecraftServer.getGlobalEventHandler().removeChild(running.adapter.eventNode)
                running.eventNodeCleanupPending = false
            }
        }
        if (!running.eventNodeCleanupPending && running.adapterStopped) started.compareAndSet(running, null)
        failure?.let { throw it }
    }

    fun placeholders(): CraftingStorePlaceholders = requireRunning().adapter.placeholders

    private fun requireRunning(): RunningCraftingStore {
        val running = started.get() ?: error("CraftingStoreModule is not initialized")
        check(running.fullyStarted && !running.cleanupStarted) { "CraftingStoreModule is not running" }
        return running
    }
}

private class RunningCraftingStore(
    val adapter: CraftingStorePluginAdapter,
) {
    @Volatile var fullyStarted = false

    @Volatile var cleanupStarted = false
    var eventNodeCleanupPending = false
    var adapterStopped = false
}
