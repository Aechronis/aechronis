package net.aechronis.vanilla

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.vanilla.listeners.WhitelistListener
import net.aechronis.vanilla.managers.Combat
import net.aechronis.vanilla.managers.Crops
import net.aechronis.vanilla.managers.KillShop
import net.aechronis.vanilla.managers.Koth
import net.aechronis.vanilla.managers.Ores
import net.aechronis.vanilla.managers.Saplings
import net.aechronis.vanilla.managers.Vanish
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent
import java.util.concurrent.CompletableFuture

class VanillaModule : AechronisModule {
    override val id = "vanilla"
    override val dependencies = setOf("utils", "combat")

    override fun initialize(context: ModuleContext) {
        Vanilla.init(c = takeConfiguration(), shutdownAction = context::shutdownServer)
        if (Vanilla.config.cropsEnabled) Crops.restoreTransientState(context.peekTransientState(CROPS_STATE_KEY))
        if (Vanilla.config.saplingsEnabled) Saplings.restoreTransientState(context.peekTransientState(SAPLINGS_STATE_KEY))
        if (Vanilla.config.oresEnabled) Ores.restoreTransientState(context.peekTransientState(ORE_COOLDOWNS_STATE_KEY))
        if (Vanilla.config.combatEnabled) Combat.restoreTransientState(context.peekTransientState(COMBAT_TAGS_STATE_KEY))
        if (Vanilla.config.commandsEnabled) Vanish.restoreTransientState(context.peekTransientState(VANISH_STATE_KEY))
        if (Vanilla.config.shopEnabled) KillShop.restoreTransientState(context.peekTransientState(SHOP_COOLDOWNS_STATE_KEY))
        // Vanish must restore first so KOTH remembers its glow as a pre-existing visibility state.
        if (Vanilla.config.kothEnabled) Koth.restoreTransientState(context.peekTransientState(KOTH_ACTIVE_STATE_KEY))
        if (Vanilla.config.whitelistEnabled) {
            context.addListener(AsyncPlayerPreLoginEvent::class.java, WhitelistListener::onPreLogin)
        }
    }

    override fun saveCheckpoint(context: ModuleContext): CompletableFuture<Void> =
        try {
            Vanilla.saveCheckpoint()
            CompletableFuture.completedFuture(null)
        } catch (error: Throwable) {
            CompletableFuture.failedFuture(error)
        }

    override fun saveState(context: ModuleContext) {
        Vanilla.saveBeforeShutdown()
        publishOrClear(context, CROPS_STATE_KEY, Vanilla.config.cropsEnabled, Crops::captureTransientState)
        publishOrClear(context, SAPLINGS_STATE_KEY, Vanilla.config.saplingsEnabled, Saplings::captureTransientState)
        publishOrClear(context, ORE_COOLDOWNS_STATE_KEY, Vanilla.config.oresEnabled, Ores::captureTransientState)
        publishOrClear(context, COMBAT_TAGS_STATE_KEY, Vanilla.config.combatEnabled, Combat::captureTransientState)
        publishOrClear(context, VANISH_STATE_KEY, Vanilla.config.commandsEnabled, Vanish::captureTransientState)
        publishOrClear(context, SHOP_COOLDOWNS_STATE_KEY, Vanilla.config.shopEnabled, KillShop::captureTransientState)
        publishOrClear(context, KOTH_ACTIVE_STATE_KEY, Vanilla.config.kothEnabled, Koth::captureTransientState)
    }

    override fun shutdown(context: ModuleContext) = Vanilla.shutdown()

    private fun publishOrClear(
        context: ModuleContext,
        key: String,
        enabled: Boolean,
        capture: () -> ByteArray,
    ) {
        if (enabled) {
            context.publishTransientState(key, capture())
        } else {
            context.clearTransientState(key)
        }
    }

    companion object {
        private var configured: VanillaConfig? = null

        fun configure(config: VanillaConfig) {
            check(configured == null) { "Vanilla was configured by more than one active composition module" }
            configured = config
        }

        private fun takeConfiguration(): VanillaConfig = configured.also { configured = null } ?: VanillaConfig()

        const val CROPS_STATE_KEY = "vanilla:crops:v1"
        const val SAPLINGS_STATE_KEY = "vanilla:saplings:v1"
        const val ORE_COOLDOWNS_STATE_KEY = "vanilla:ore-cooldowns:v1"
        const val COMBAT_TAGS_STATE_KEY = "vanilla:combat-tags:v1"
        const val VANISH_STATE_KEY = "vanilla:vanish:v1"
        const val SHOP_COOLDOWNS_STATE_KEY = "vanilla:shop-cooldowns:v1"
        const val KOTH_ACTIVE_STATE_KEY = "vanilla:koth-active:v1"
    }
}
