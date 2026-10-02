package net.aechronis.votifier

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext

class VotifierModule : AechronisModule {
    override val id = "votifier"
    override val dependencies = setOf("gems", "vanilla")

    override fun initialize(context: ModuleContext) = VotifierIntegration.initialize()

    override fun prepareForShutdown(context: ModuleContext) = VotifierIntegration.shutdown()

    override fun shutdown(context: ModuleContext) = VotifierIntegration.shutdown()
}
