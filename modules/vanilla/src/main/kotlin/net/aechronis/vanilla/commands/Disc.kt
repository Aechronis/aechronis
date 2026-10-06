package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.DiscMenu
import net.minestom.server.entity.Player

class Disc : Command("disc", null, "discs") {
    init {
        setDefaultExecutor { player: Player, _ -> DiscMenu.open(player) }
    }
}
