package net.aechronis.votifier

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.votifier.commands.Vote
import net.aechronis.votifier.managers.VoteLinks
import java.nio.file.Path

class VotifierModule : AechronisModule {
    override val id = "votifier"
    override val dependencies = setOf("utils", "crates", "economy")

    override fun initialize(context: ModuleContext) {
        VotifierIntegration.initialize()
        VoteLinks.init(Path.of("votifier", "vote.txt"))
        ModuleCommands.register(Vote())
    }

    override fun prepareForShutdown(context: ModuleContext) = VotifierIntegration.shutdown()

    override fun shutdown(context: ModuleContext) = VotifierIntegration.shutdown()
}
