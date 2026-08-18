package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import net.aechronis.vanilla.managers.Shutdown as ShutdownManager

class Shutdown : Command("shutdown", "vanilla.shutdown") {
    private val timeArg = ArgumentType.Long("time").min(1)

    init {
        setDefaultExecutor { sender: Player, _ ->
            sender.sendMessage(Component.text("Usage: /shutdown <time in seconds>", NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ sender: Player, context ->
            val seconds = context[timeArg]
            if (!ShutdownManager.schedule(seconds)) {
                sender.sendMessage(Component.text("A shutdown is already scheduled.", NamedTextColor.RED))
            }
        }, timeArg)
    }
}
