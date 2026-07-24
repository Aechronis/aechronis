package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Commands.sendMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import kotlin.collections.joinToString

class Message : Command("message", null, "msg", "tell", "whisper", "w") {
    val playerArg = ArgumentType.Entity("player-name").singleEntity(true).onlyPlayers(true)
    val messageArg = ArgumentType.StringArray("message")

    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/message <player> <message>", NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ sender: Player, context ->
            sendMessage(sender, context[playerArg].findFirstPlayer(sender), context[messageArg].joinToString(" "))
        }, playerArg, messageArg)
    }
}
