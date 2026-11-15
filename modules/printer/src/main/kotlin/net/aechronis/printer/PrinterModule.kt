package net.aechronis.printer

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext

class PrinterModule : AechronisModule {
    override val id = "printer"
    override val dependencies = setOf("utils", "grimac", "combat")

    override fun initialize(context: ModuleContext) {
        Printer.init(context)
        Printer.restoreTransientState(context.peekTransientState(STATE_KEY))
        // A later re-enable after the module was switched off should start everyone fresh.
        context.clearTransientState(STATE_KEY)
        ModuleCommands.register(PrinterCommand())
    }

    // Grim reloads take this module down too; carry printer players across instead of dropping them.
    override fun saveState(context: ModuleContext) = context.publishTransientState(STATE_KEY, Printer.captureTransientState())

    override fun shutdown(context: ModuleContext) = Printer.shutdown()

    private companion object {
        const val STATE_KEY = "printer:enabled:v1"
    }
}
