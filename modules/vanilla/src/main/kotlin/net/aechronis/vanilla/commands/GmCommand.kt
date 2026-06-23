package net.aechronis.vanilla.commands

import net.aechronis.vanilla.utils.Command
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player

class GmCommand : Command("gmc", "vanilla.gamemode", "gms", "gmsp", "gma") {
    init {
        setDefaultExecutor { player: Player, context ->
            val gameMode =
                when (context.commandName) {
                    "gmc" -> GameMode.CREATIVE
                    "gms" -> GameMode.SURVIVAL
                    "gmsp" -> GameMode.SPECTATOR
                    "gma" -> GameMode.ADVENTURE
                    else -> return@setDefaultExecutor
                }

            player.gameMode = gameMode
        }
    }
}
