package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player

class Back : Command("back", "vanilla.back", "return") {
    init {
        setDefaultExecutor { player: Player, _ ->
            val last =
                Commands.getLastLocation(player) ?: run {
                    player.sendMessage(Component.text("No previous location to return to.").color(NamedTextColor.RED))
                    return@setDefaultExecutor
                }

            player.teleport(last)
        }
    }
}
