package net.aechronis.crates

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext

class CratesModule : AechronisModule {
    override val id = "crates"

    // Resolve outstanding rewards before vanilla captures player inventories during teardown.
    override val dependencies = setOf("utils", "vanilla")

    override fun initialize(context: ModuleContext) = Crates.init(takeConfiguration())

    override fun prepareForShutdown(context: ModuleContext) = context.captureLive(Crates::finishPendingRolls)

    override fun shutdown(context: ModuleContext) = Crates.shutdown()

    companion object {
        private var configured: CratesConfig? = null

        fun configure(config: CratesConfig) {
            check(configured == null) { "Crates was configured by more than one active composition module" }
            configured = config
        }

        private fun takeConfiguration(): CratesConfig = (configured ?: CratesConfig()).also { configured = null }
    }
}
