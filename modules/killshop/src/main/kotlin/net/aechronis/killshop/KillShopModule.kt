package net.aechronis.killshop

import net.aechronis.killshop.commands.Shop
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.vanilla.Vanilla
import net.minestom.server.event.player.PlayerSpawnEvent
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class KillShopModule : AechronisModule {
    override val id = "killshop"
    override val dependencies = setOf("utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        KillShopPoints.init(Path.of("killshop", "points.json"), Path.of(Vanilla.config.path, Vanilla.config.playerDataPath))
        context.addListener(PlayerSpawnEvent::class.java) { event ->
            if (event.isFirstSpawn) {
                runCatching { KillShopPoints.balance(event.player) }.onFailure { error ->
                    System.err.println("Failed to load kill-shop points for ${event.player.uuid}: ${error.message}")
                }
            }
        }
        val config = takeConfiguration()
        KillShop.init(config)
        if (config.enabled) {
            KillShop.restoreTransientState(
                context.peekTransientState(COOLDOWNS_STATE_KEY) ?: context.peekTransientState(LEGACY_COOLDOWNS_STATE_KEY),
            )
            ModuleCommands.register(Shop())
        }
    }

    override fun saveCheckpoint(context: ModuleContext): CompletableFuture<Void> =
        context.captureLive { KillShopPoints.save(KillShopPoints.snapshot()) }

    override fun saveState(context: ModuleContext) {
        context.captureLive { KillShopPoints.save(KillShopPoints.snapshot()) }.join()
        context.publishTransientState(COOLDOWNS_STATE_KEY, KillShop.captureTransientState())
    }

    override fun shutdown(context: ModuleContext) {
        KillShop.shutdown()
        KillShopPoints.shutdown()
    }

    companion object {
        const val COOLDOWNS_STATE_KEY = "killshop:cooldowns:v1"
        private const val LEGACY_COOLDOWNS_STATE_KEY = "vanilla:shop-cooldowns:v1"
        private var configured: ShopConfig? = null

        fun configure(config: ShopConfig) {
            check(configured == null) { "Kill shop was configured by more than one active composition module" }
            configured = config
        }

        private fun takeConfiguration(): ShopConfig = (configured ?: ShopConfig()).also { configured = null }
    }
}
