package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Commands.getLastSender
import net.aechronis.vanilla.managers.Commands.sendMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Reply : Command("reply", null, "r") {
    val messageArg = ArgumentType.StringArray("message")

    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/reply <message>", NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ sender: Player, context ->
            sendMessage(sender, getLastSender(sender), context[messageArg].joinToString(" "))
        }, messageArg)
    }
}
