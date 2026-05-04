package net.aechronis.vanilla.commands

import net.aechronis.vanilla.utils.ChatColor
import net.aechronis.vanilla.utils.Message
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import kotlin.collections.joinToString

val playerLastSender = HashMap<Player, Player>()

private fun sendMessage(
    sender: Player,
    receiver: Player?,
    message: String,
) {
    if (receiver == null) {
        Message.error(sender, "Player not found.")
        return
    }

    Message.print(
        sender,
        "${ChatColor.AQUA}You ${ChatColor.WHITE}-> ${ChatColor.AQUA}${receiver.username}: ${ChatColor.WHITE}$message",
    )

    Message.print(
        receiver,
        "${ChatColor.AQUA}${sender.username} ${ChatColor.WHITE}-> ${ChatColor.AQUA}You: ${ChatColor.WHITE}$message",
    )

    playerLastSender[receiver] = sender
    playerLastSender[sender] = receiver
}

class MessageCommand : Command("message", null, "msg", "tell", "whisper", "w") {
    val playerArg = ArgumentType.Entity("player-name").singleEntity(true).onlyPlayers(true)
    val messageArg = ArgumentType.StringArray("message")

    init {
        setDefaultExecutor { player: Player, _ ->
            Message.print(player, "Usage:")
            Message.print(player, "/message <player> <message>")
        }

        addSyntax({ sender: Player, context ->
            sendMessage(sender, context[playerArg].findFirstPlayer(sender), context[messageArg].joinToString(" "))
        }, playerArg, messageArg)
    }
}

class ReplyCommand : Command("reply", null, "r") {
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
