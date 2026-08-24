package net.aechronis.craftingstore

import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import java.util.concurrent.atomic.AtomicReference

/** Native CraftingStore integration. The GUI is intentionally optional; donation
 * queue polling and server-command execution work without a menu implementation. */
object CraftingStoreModule {
    private val started = AtomicReference<CraftingStorePluginAdapter?>()

    val eventNode: EventNode<Event>
        get() = requireAdapter().eventNode

    @Synchronized
    fun initialize(options: CraftingStoreOptions = CraftingStoreOptions()) {
        if (started.get() != null) return
        val config = ConfigStore(options.dataDirectory)
        config.reload()
        val node = EventNode.all("craftingstore")
        val adapter = CraftingStorePluginAdapter(options, config, node)
        MinecraftServer.getGlobalEventHandler().addChild(node)
        started.set(adapter)
        try {
            adapter.start()
        } catch (error: Throwable) {
            MinecraftServer.getGlobalEventHandler().removeChild(node)
            started.set(null)
            throw error
        }
    }

    fun shutdown() {
        val adapter = started.getAndSet(null) ?: return
        adapter.shutdown()
    }

    fun placeholders(): CraftingStorePlaceholders = requireAdapter().placeholders

    private fun requireAdapter() = started.get() ?: error("CraftingStoreModule is not initialized")
}
