package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Ores
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Ore : Command("ore", "vanilla.ore") {
    private val timeArg = ArgumentType.Integer("time")

    init {
        addSyntax({ player: Player, context ->
            val time = context[timeArg].toLong()
            if (time <= 0 || !Ores.configure(player, time)) {
                player.sendMessage(Component.text("Look at an ore and provide a positive time in seconds.", NamedTextColor.RED))
            }
        }, timeArg)
        addSyntax({ player: Player, _ ->
            if (Ores.remove(player)) {
                player.sendMessage(Component.text("Removed regenerating ore.", NamedTextColor.GREEN))
            } else {
                player.sendMessage(Component.text("Look at a configured ore to remove it.", NamedTextColor.RED))
            }
        }, ArgumentType.Literal("remove"))
    }
}
