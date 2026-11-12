package net.aechronis.staff.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.aechronis.staff.managers.Shutdown as ShutdownManager

class Shutdown : Command("shutdown", "staff.shutdown") {
    private val timeArg = ArgumentType.Long("time").min(1)

    init {
        setSenderDefaultExecutor { sender, _ ->
            sender.sendMessage(Component.text("Usage: /shutdown <time in seconds>", NamedTextColor.LIGHT_PURPLE))
        }

        addSenderSyntax({ sender, context ->
            val seconds = context[timeArg]
            if (!ShutdownManager.schedule(seconds)) {
                sender.sendMessage(Component.text("A shutdown is already scheduled.", NamedTextColor.RED))
            }
        }, timeArg)
    }
}
