package net.aechronis.vanilla.commands

import net.aechronis.vanilla.managers.Commands.getLastSender
import net.aechronis.vanilla.managers.Commands.sendMessage
import net.aechronis.vanilla.utils.Message
import net.aechronis.vanilla.utils.VanillaCommand
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Reply : VanillaCommand("reply", null, "r") {
    val messageArg = ArgumentType.StringArray("message")

    init {
        setDefaultExecutor { player: Player, _ ->
            Message.print(player, "Usage:")
            Message.print(player, "/reply <message>")
        }

        addSyntax({ sender: Player, context ->
            sendMessage(sender, getLastSender(sender), context[messageArg].joinToString(" "))
        }, messageArg)
    }
}
