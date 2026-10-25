package net.aechronis.vanilla

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleStartupTimings.measure
import net.aechronis.vanilla.listeners.WhitelistListener
import net.aechronis.vanilla.managers.Crops
import net.aechronis.vanilla.managers.Horses
import net.aechronis.vanilla.managers.Ores
import net.aechronis.vanilla.managers.Saplings
import net.aechronis.vanilla.managers.Vanish
import net.aechronis.vanilla.objects.Recipe
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent
import java.util.concurrent.CompletableFuture

class VanillaModule : AechronisModule {
    override val id = "vanilla"
    override val dependencies = setOf("utils", "combat")

    override fun initialize(context: ModuleContext) {
        Vanilla.init(c = takeConfiguration(), shutdownAction = context::shutdownServer, instance = context.instance)
        measure("Restore transient state") {
            if (Vanilla.config.cropsEnabled) Crops.restoreTransientState(context.peekTransientState(CROPS_STATE_KEY))
            if (Vanilla.config.saplingsEnabled) Saplings.restoreTransientState(context.peekTransientState(SAPLINGS_STATE_KEY))
            if (Vanilla.config.oresEnabled) Ores.restoreTransientState(context.peekTransientState(ORE_COOLDOWNS_STATE_KEY))
            if (Vanilla.config.commandsEnabled) Vanish.restoreTransientState(context.peekTransientState(VANISH_STATE_KEY))
        }
        if (Vanilla.config.whitelistEnabled) {
            context.addListener(AsyncPlayerPreLoginEvent::class.java, WhitelistListener::onPreLogin)
        }
    }

    override fun prepareForShutdown(context: ModuleContext) {
        // Riders are released before player positions are saved.
        Horses.prepareForShutdown()
    }

    override fun saveCheckpoint(context: ModuleContext): CompletableFuture<Void> = context.captureLive(Vanilla::saveCheckpoint)

    override fun saveState(context: ModuleContext) {
        Vanilla.saveBeforeShutdown(context)
        publishOrClear(context, CROPS_STATE_KEY, Vanilla.config.cropsEnabled, Crops::captureTransientState)
        publishOrClear(context, SAPLINGS_STATE_KEY, Vanilla.config.saplingsEnabled, Saplings::captureTransientState)
        publishOrClear(context, ORE_COOLDOWNS_STATE_KEY, Vanilla.config.oresEnabled, Ores::captureTransientState)
        publishOrClear(context, VANISH_STATE_KEY, Vanilla.config.commandsEnabled, Vanish::captureTransientState)
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
        private val additionalRecipes = mutableListOf<Recipe>()

        fun configure(config: VanillaConfig) {
            check(configured == null) { "Vanilla was configured by more than one active composition module" }
            configured = config
        }

        /** Contributes shared recipes during configuration, independently of the active iteration. */
        fun addRecipes(recipes: List<Recipe>) {
            additionalRecipes.addAll(recipes)
        }

        private fun takeConfiguration(): VanillaConfig {
            val config = configured ?: VanillaConfig()
            val combined =
                config.copy(
                    recipesConfig = config.recipesConfig.copy(recpies = additionalRecipes + config.recipesConfig.recpies),
                )
            configured = null
            additionalRecipes.clear()
            return combined
        }

        const val CROPS_STATE_KEY = "vanilla:crops:v1"
        const val SAPLINGS_STATE_KEY = "vanilla:saplings:v1"
        const val ORE_COOLDOWNS_STATE_KEY = "vanilla:ore-cooldowns:v1"
        const val VANISH_STATE_KEY = "vanilla:vanish:v1"
    }
}
