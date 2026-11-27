package net.aechronis.staff.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType

class Broadcast : Command("broadcast", "staff.broadcast") {
    val messageArg = ArgumentType.StringArray("message")

    init {
        setSenderDefaultExecutor { sender, _ ->
            sender.sendMessage(Component.text("Usage:").color(NamedTextColor.LIGHT_PURPLE))
            sender.sendMessage(Component.text("/broadcast <message>").color(NamedTextColor.LIGHT_PURPLE))
        }

        addSenderSyntax({ _, context ->
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
