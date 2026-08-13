package net.aechronis.server.commands

import net.aechronis.nodes.Nodes
import net.aechronis.server.Server
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component

class SetSpawnCommand : Command("setspawn", "server.setspawn") {
    init {
        setDefaultExecutor { player, _ ->
            Server.setSpawnPoint(player.position)
            Nodes.config.defaultRespawnPoint = Server.spawnPoint
            player.sendMessage(Component.text("World spawn set to ${Server.spawnPoint}"))
        }
    }
}
