package net.aechronis.music.commands

import net.aechronis.music.managers.DiscMenu
import net.aechronis.utils.Command
import net.minestom.server.entity.Player

class Disc : Command("disc", null, "discs") {
    init {
        setDefaultExecutor { player: Player, _ -> DiscMenu.open(player) }
    }
}
