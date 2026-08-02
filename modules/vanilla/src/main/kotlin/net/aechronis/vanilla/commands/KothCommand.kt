package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Koth
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class KothCommand : Command("koth", "vanilla.koth") {
    private val nameArg = ArgumentType.Word("name")

    init {
        setDefaultExecutor { sender: Player, _ -> sendList(sender) }

        addSyntax({ sender: Player, _ -> sendList(sender) }, ArgumentType.Literal("list"))

        addSyntax({ sender: Player, context ->
            val name = context[nameArg]
            val message =
                if (Koth.start(name)) {
                    "Started KOTH $name."
                } else {
                    "Unable to start KOTH $name. It may not exist or is already active."
                }
            sender.sendMessage(Component.text(message, NamedTextColor.LIGHT_PURPLE))
        }, ArgumentType.Literal("start"), nameArg)

        addSyntax({ sender: Player, context ->
            val name = context[nameArg]
            val message =
                if (Koth.stop(name)) {
                    "Stopped KOTH $name."
                } else {
                    "KOTH $name is not active."
                }
            sender.sendMessage(Component.text(message, NamedTextColor.LIGHT_PURPLE))
        }, ArgumentType.Literal("stop"), nameArg)

        addSyntax({ sender: Player, _ -> sendStatuses(sender) }, ArgumentType.Literal("status"))
        addSyntax({ sender: Player, context ->
            val name = context[nameArg]
            sender.sendMessage(
                Component.text(
                    Koth.status(name) ?: "Unknown KOTH: $name",
                    NamedTextColor.LIGHT_PURPLE,
                ),
            )
        }, ArgumentType.Literal("status"), nameArg)
    }

    private fun sendList(sender: Player) {
        val names = Koth.configuredNames()
        sender.sendMessage(
            Component.text(
                if (names.isEmpty()) "No KOTHS are configured." else "KOTHS: ${names.joinToString(", ")}",
                NamedTextColor.LIGHT_PURPLE,
            ),
        )
    }

    private fun sendStatuses(sender: Player) {
        val names = Koth.configuredNames()
        if (names.isEmpty()) {
            sender.sendMessage(Component.text("No KOTHS are configured.", NamedTextColor.LIGHT_PURPLE))
            return
        }
        names.forEach { name ->
            sender.sendMessage(Component.text(Koth.status(name)!!, NamedTextColor.LIGHT_PURPLE))
        }
    }
}
