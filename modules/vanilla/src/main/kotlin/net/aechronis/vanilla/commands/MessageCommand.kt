package net.aechronis.vanilla.commands

import net.aechronis.vanilla.managers.Commands.sendMessage
import net.aechronis.vanilla.utils.Command
import net.aechronis.vanilla.utils.Message
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import kotlin.collections.joinToString

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
