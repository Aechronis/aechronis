package net.aechronis.combat

import net.aechronis.combat.storage.VehiclePersistence
import net.aechronis.combat.tasks.BlockRestoreManager
import net.aechronis.combat.utils.GunHandSkins
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleStartupTimings.measure

class CombatModule : AechronisModule {
    override val id = "combat"
    override val dependencies = setOf("utils")

    override fun initialize(context: ModuleContext) {
        Combat.initialize()
        measure("Hand skins") { GunHandSkins.initialize(context) }
        measure("Vehicle persistence") { Combat.initializeVehiclePersistence(context) }
    }

    override fun prepareForShutdown(context: ModuleContext) {
        BlockRestoreManager.shutdown()
        // All modules prepare before Vanilla captures player positions in its save hook.
        VehiclePersistence.prepareForShutdown()
    }

    override fun saveState(context: ModuleContext) = VehiclePersistence.save()

    override fun shutdown(context: ModuleContext) {
        GunHandSkins.shutdown()
        Combat.shutdown()
    }
}
