package net.aechronis.combat

import net.aechronis.combat.tasks.BlockRestoreManager
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext

class CombatModule : AechronisModule {
    override val id = "combat"
    override val dependencies = setOf("utils", "watchdog")

    override fun initialize(context: ModuleContext) = Combat.initialize()

    override fun prepareForShutdown(context: ModuleContext) = BlockRestoreManager.shutdown()

    override fun shutdown(context: ModuleContext) = Combat.shutdown()
}
