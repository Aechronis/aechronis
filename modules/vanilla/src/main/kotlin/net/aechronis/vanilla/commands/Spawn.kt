package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.SpawnTeleport
import net.minestom.server.entity.Player

class Spawn : Command("spawn") {
    init {
        setDefaultExecutor { player: Player, _ -> SpawnTeleport.start(player) }
    }
}
