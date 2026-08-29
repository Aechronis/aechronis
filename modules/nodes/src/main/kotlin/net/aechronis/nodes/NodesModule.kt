package net.aechronis.nodes

import net.aechronis.combat.tasks.BlockRestoreManager
import net.aechronis.server.events.SpawnPointChangedEvent
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import java.util.concurrent.CompletableFuture

class NodesModule : AechronisModule {
    override val id = "nodes"
    override val dependencies = setOf("utils", "combat", "vanilla", "worldedit")

    override fun initialize(context: ModuleContext) {
        Nodes.initialize(takeConfiguration(context))
        context.addListener(SpawnPointChangedEvent::class.java) { event ->
            Nodes.config.defaultRespawnPoint = event.spawnPoint
        }
    }

    override fun saveCheckpoint(context: ModuleContext): CompletableFuture<Void> = Nodes.saveWorld(checkIfNeedsSave = true, async = true)

    override fun prepareForShutdown(context: ModuleContext) {
        // Combat is a dependency and normally prepares after Nodes. Restore its temporary block
        // replacements first so clearing a war flag remains authoritative in the core checkpoint.
        BlockRestoreManager.shutdown()
        Nodes.prepareForShutdown()
        // Nodes teardown removes temporary war structures and performs its final durable save.
        // Run it before the core world checkpoint so shutdown/reload cannot persist ghost flags.
        Nodes.cleanup()
    }

    override fun shutdown(context: ModuleContext) = Nodes.cleanup()

    companion object {
        private var configured: NodesConfig? = null

        fun configure(config: NodesConfig) {
            check(configured == null) { "Nodes was configured by more than one active composition module" }
            configured = config
        }

        private fun takeConfiguration(context: ModuleContext): NodesConfig = configured.also { configured = null } ?: NodesConfig(defaultRespawnPoint = context.spawnPoint)
    }
}
