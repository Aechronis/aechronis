package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Commands
import net.minestom.server.entity.Player

class EnderChest : Command("ec", "vanilla.ec") {
    init {
        setDefaultExecutor { player: Player, _ ->
            Commands.openEnderChest(player, player)
        }
    }
}
