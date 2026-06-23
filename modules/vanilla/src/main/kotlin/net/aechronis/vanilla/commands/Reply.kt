package net.aechronis.vanilla.commands

import net.aechronis.vanilla.managers.Commands.playerLastSender
import net.aechronis.vanilla.managers.Commands.sendMessage
import net.aechronis.vanilla.utils.Command
import net.aechronis.vanilla.utils.Message
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Reply : Command("reply", null, "r") {
    val messageArg = ArgumentType.StringArray("message")

    init {
        setDefaultExecutor { player: Player, _ ->
            Message.print(player, "Usage:")
            Message.print(player, "/reply <message>")
        }

        addSyntax({ sender: Player, context ->
            sendMessage(sender, playerLastSender[sender], context[messageArg].joinToString(" "))
        }, messageArg)
    }
}
