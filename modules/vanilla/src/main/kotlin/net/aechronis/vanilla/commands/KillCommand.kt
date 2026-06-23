package net.aechronis.vanilla.commands

import net.aechronis.vanilla.utils.Command

class KillCommand : Command("kill", "vanilla.kill") {
    init {
        setDefaultExecutor { player, _ ->
            player.kill()
        }
    }
}
