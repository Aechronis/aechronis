package net.aechronis.duels

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext

class DuelsModule : AechronisModule {
    override val id = "duels"
    override val dependencies = setOf("utils", "combat", "vanilla")

    override fun initialize(context: ModuleContext) {
        MatchListener.register(context)
        DuelMenus.register(context)
        ModuleCommands.register(DuelCommand())
    }

    override fun prepareForShutdown(context: ModuleContext) {
        // Players get their inventories back before their data is saved.
        MatchManager.abortAll()
        DuelMenus.closeAll()
        Invites.clear()
    }

    override fun shutdown(context: ModuleContext) {
        MatchManager.abortAll()
        DuelMenus.closeAll()
        Invites.clear()
    }
}
