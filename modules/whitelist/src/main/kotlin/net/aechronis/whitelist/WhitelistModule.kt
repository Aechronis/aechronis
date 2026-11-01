package net.aechronis.whitelist

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.whitelist.listeners.WhitelistListener
import net.aechronis.whitelist.managers.Whitelist
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent
import java.nio.file.Path
import net.aechronis.whitelist.commands.Whitelist as WhitelistCommand

class WhitelistModule : AechronisModule {
    override val id = "whitelist"
    override val dependencies = setOf("utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        Whitelist.init(Path.of("whitelist", "whitelist.json"))
        context.addListener(AsyncPlayerPreLoginEvent::class.java, WhitelistListener::onPreLogin)
        ModuleCommands.register(WhitelistCommand())
    }
}
