package net.aechronis.vanilla.commands

import net.aechronis.vanilla.utils.Command
import net.minestom.server.entity.Player

class Kill : Command("kill", "vanilla.kill") {
    init {
        setDefaultExecutor { player: Player, _ ->
            player.kill()
        }
    }
}
