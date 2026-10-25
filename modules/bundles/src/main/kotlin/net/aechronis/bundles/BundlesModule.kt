package net.aechronis.bundles

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext

class BundlesModule : AechronisModule {
    override val id = "bundles"
    override val dependencies = setOf("utils")

    override fun initialize(context: ModuleContext) = Bundles.init(takeConfiguration())

    companion object {
        private var configured: BundlesConfig? = null

        fun configure(config: BundlesConfig) {
            check(configured == null) { "Bundles was configured by more than one active composition module" }
            configured = config
        }

        private fun takeConfiguration(): BundlesConfig = (configured ?: BundlesConfig()).also { configured = null }
    }
}
