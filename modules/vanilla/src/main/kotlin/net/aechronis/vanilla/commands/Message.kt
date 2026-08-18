package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Commands.isBlocked
import net.aechronis.vanilla.managers.Commands.sendMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import kotlin.collections.joinToString

class Message : Command("message", null, "msg", "tell", "whisper", "w") {
    val playerArg = PlayerTargets.argument("player-name")
    val messageArg = ArgumentType.StringArray("message")

    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/message <player> <message>", NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ sender: Player, context ->
            val message = context[messageArg].joinToString(" ")
            val targets = PlayerTargets.resolve(sender, context[playerArg]) ?: return@addSyntax
            if (context[playerArg] == "*") {
                targets.filter { !isBlocked(sender, it) }.forEach { target ->
                    target.sendMessage(Component.text("${sender.username} Whispered: $message").color(NamedTextColor.LIGHT_PURPLE))
                }
                sender.sendMessage(Component.text("Message sent to ${targets.size} player(s).", NamedTextColor.LIGHT_PURPLE))
            } else {
                sendMessage(sender, targets.singleOrNull(), message)
            }
        }, playerArg, messageArg)
    }
}
