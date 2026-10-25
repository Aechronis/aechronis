package net.aechronis.treefeller

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext

class TreeFellerModule : AechronisModule {
    override val id = "treefeller"
    override val dependencies = setOf("vanilla")

    override fun initialize(context: ModuleContext) = TreeFeller.init(takeConfiguration())

    override fun shutdown(context: ModuleContext) = TreeFeller.shutdown()

    companion object {
        private var configured: TreeFellerConfig? = null

        fun configure(config: TreeFellerConfig) {
            check(configured == null) { "Tree felling was configured by more than one active composition module" }
            configured = config
        }

        private fun takeConfiguration(): TreeFellerConfig = (configured ?: TreeFellerConfig()).also { configured = null }
    }
}
