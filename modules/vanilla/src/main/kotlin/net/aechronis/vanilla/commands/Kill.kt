package net.aechronis.vanilla.commands

import net.aechronis.vanilla.utils.VanillaCommand
import net.minestom.server.entity.Player

class Kill : VanillaCommand("kill", "vanilla.kill") {
    init {
        setDefaultExecutor { player: Player, _ ->
            player.kill()
        }
    }
}
