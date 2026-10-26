package net.aechronis.economy

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import java.util.concurrent.CompletableFuture

class EconomyModule : AechronisModule {
    override val id = "economy"
    override val dependencies = setOf("combat", "utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        Economy.initialize()
        BlockShop.init()
        MiningBoostManager.initialize()
        MiningRewards.init(takeConfiguration())
        context.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            Economy.rememberPlayer(event.player)
        }
        context.addListener(PlayerSpawnEvent::class.java) { event ->
            if (event.isFirstSpawn) MiningBoostManager.onPlayerJoin(event.player)
        }
        context.addListener(PlayerDisconnectEvent::class.java) { event ->
            MiningBoostManager.onPlayerQuit(event.player)
        }
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            Economy.rememberPlayer(player)
            MiningBoostManager.onPlayerJoin(player)
        }
    }

    override fun saveCheckpoint(context: ModuleContext): CompletableFuture<Void> {
        MiningBoostManager.save()
        return CompletableFuture.completedFuture(null)
    }

    override fun saveState(context: ModuleContext) {
        MiningBoostManager.save()
    }

    override fun shutdown(context: ModuleContext) {
        MiningRewards.shutdown()
        BlockShop.shutdown()
        MiningBoostManager.stop()
        Economy.shutdown()
    }

    companion object {
        private var configured: EconomyConfig? = null

        fun configure(config: EconomyConfig) {
            check(configured == null) { "Economy was configured by more than one active composition module" }
            configured = config
        }

        private fun takeConfiguration(): EconomyConfig = (configured ?: EconomyConfig()).also { configured = null }
    }
}
