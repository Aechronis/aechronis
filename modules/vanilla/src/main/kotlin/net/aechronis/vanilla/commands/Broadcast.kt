package net.aechronis.vanilla.commands

import net.aechronis.vanilla.utils.Command
import net.aechronis.vanilla.utils.Message
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Broadcast : Command("broadcast", "vanilla.broadcast") {
    val messageArg = ArgumentType.StringArray("message")

    init {
        setDefaultExecutor { player, _ ->
            Message.print(player, "Usage:")
            Message.print(player, "/broadcast <message>")
        }

        addSyntax({ sender: Player, context ->
            val text = context.get(messageArg).joinToString(" ")
            val component =
                Component
                    .text("[brodcast] ", NamedTextColor.GOLD)
                    .append(Component.text(text, NamedTextColor.GREEN))

            for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                player.sendMessage(component)
            }
        }, messageArg)
    }
}
