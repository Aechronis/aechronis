package net.aechronis.crates

import net.aechronis.crates.commands.CratesCommand
import net.aechronis.crates.listeners.CratesListener
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import java.nio.file.Path

class CratesModule : AechronisModule {
    override val id = "crates"
    override val dependencies = setOf("utils")

    override fun initialize(context: ModuleContext) {
        Crates.init(Path.of("crates"), takeDefaults())
        CratesListener.init()
        ModuleCommands.register(CratesCommand())
    }

    override fun shutdown(context: ModuleContext) = Crates.shutdown()

    companion object {
        private var defaults: List<CrateDefaults>? = null

        /** Called by the active iteration during configure to ship its crates. */
        fun configure(crates: List<CrateDefaults>) {
            check(defaults == null) { "Crates was configured by more than one active composition module" }
            defaults = crates
        }

        private fun takeDefaults(): List<CrateDefaults> = defaults.orEmpty().also { defaults = null }
    }
}
