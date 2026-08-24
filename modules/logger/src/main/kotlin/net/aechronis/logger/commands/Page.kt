package net.aechronis.logger.commands

import net.aechronis.logger.utils.Pages
import net.aechronis.utils.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Page : Command("page", "logger.page") {
    init {
        val number = ArgumentType.Integer("number")
        addSyntax({ sender: Player, context -> Pages.showPage(sender, context.get(number)) }, number)
    }
}
