package net.aechronis.guard

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext

class GuardModule : AechronisModule {
    override val id = "guard"
    override val dependencies = setOf("utils", "worldedit", "combat")

    override fun initialize(context: ModuleContext) = Guard.init(GuardConfig())

    override fun saveState(context: ModuleContext) = Guard.save()

    override fun shutdown(context: ModuleContext) = Guard.shutdown()
}
