package net.aechronis.combat

import net.aechronis.combat.managers.CombatTags
import net.aechronis.combat.storage.VehiclePersistence
import net.aechronis.combat.tasks.BlockRestoreManager
import net.aechronis.combat.utils.GunHandSkins
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleStartupTimings.measure
import java.nio.file.Path

class CombatModule : AechronisModule {
    override val id = "combat"
    override val dependencies = setOf("utils")

    override fun initialize(context: ModuleContext) {
        Combat.initialize(takeConfiguration())
        measure("Restore combat tags") { CombatTags.restoreTransientState(context.peekTransientState(COMBAT_TAGS_STATE_KEY)) }
        measure("Hand skins") { GunHandSkins.initialize(context) }
        measure("Vehicle persistence") {
            try {
                VehiclePersistence.initialize(Path.of("combat", "vehicles.json"), context.instance)
            } catch (error: Throwable) {
                // Discard partially restored vehicles without saving over the authoritative state.
                runCatching(VehiclePersistence::shutdown).onFailure(error::addSuppressed)
                throw error
            }
        }
    }

    override fun prepareForShutdown(context: ModuleContext) {
        BlockRestoreManager.shutdown()
        // All modules prepare before Vanilla captures player positions in its save hook.
        VehiclePersistence.prepareForShutdown()
    }

    override fun saveState(context: ModuleContext) {
        context.publishTransientState(COMBAT_TAGS_STATE_KEY, CombatTags.captureTransientState())
        VehiclePersistence.save()
    }

    override fun shutdown(context: ModuleContext) {
        GunHandSkins.shutdown()
        Combat.shutdown()
    }

    companion object {
        private var configured: CombatConfig? = null

        fun configure(config: CombatConfig) {
            check(configured == null) { "Combat was configured by more than one active composition module" }
            configured = config
        }

        private fun takeConfiguration(): CombatConfig {
            val config = configured ?: CombatConfig()
            configured = null
            return config
        }

        // Keep the snapshot key compatible with active tags from before the ownership move.
        const val COMBAT_TAGS_STATE_KEY = "vanilla:combat-tags:v1"
    }
}
