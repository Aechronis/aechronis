package net.aechronis.staff

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.staff.commands.Broadcast
import net.aechronis.staff.commands.InventorySee
import net.aechronis.staff.managers.InventoryViews
import net.aechronis.staff.managers.Shutdown
import net.aechronis.staff.managers.Vanish
import net.aechronis.staff.commands.Shutdown as ShutdownCommand
import net.aechronis.staff.commands.Vanish as VanishCommand

class StaffModule : AechronisModule {
    override val id = "staff"
    override val dependencies = setOf("utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        Shutdown.configure(context::shutdownServer)
        Vanish.init(context)
        Vanish.restoreTransientState(context.peekTransientState(VANISH_STATE_KEY))
        InventoryViews.init()
        ModuleCommands.register(Broadcast(), VanishCommand(), InventorySee(), ShutdownCommand())
    }

    override fun prepareForShutdown(context: ModuleContext) {
        context.captureLive(InventoryViews::closeAll)
    }

    override fun saveState(context: ModuleContext) {
        context.publishTransientState(VANISH_STATE_KEY, context.captureLive(Vanish::captureTransientState))
    }

    override fun shutdown(context: ModuleContext) {
        InventoryViews.closeAll()
        Vanish.shutdown()
    }

    companion object {
        const val VANISH_STATE_KEY = "staff:vanish:v1"
    }
}
