package net.aechronis.vanilla.commands

import net.aechronis.vanilla.managers.Commands
import net.aechronis.vanilla.utils.Message
import net.aechronis.vanilla.utils.VanillaCommand
import net.minestom.server.entity.Player

class Back : VanillaCommand("back", "vanilla.back", "return") {
    init {
        setDefaultExecutor { player: Player, _ ->
            val last =
                Commands.getLastLocation(player) ?: run {
                    Message.error(player, "No previous location to return to.")
                    return@setDefaultExecutor
                }

            player.teleport(last)
        }
    }
}
