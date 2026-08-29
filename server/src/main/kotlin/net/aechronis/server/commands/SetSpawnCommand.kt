package net.aechronis.server.commands

import net.aechronis.server.Server
import net.aechronis.server.hasPermission
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.Command
import net.minestom.server.entity.Player

class SetSpawnCommand : Command("setspawn") {
    init {
        setDefaultExecutor { sender, _ ->
            val player = sender as? Player
            if (player == null) {
                sender.sendMessage(Component.text("This command can only be used by players", NamedTextColor.RED))
                return@setDefaultExecutor
            }
            if (!player.hasPermission(PERMISSION)) {
                player.sendMessage(Component.text("You don't have permission to use this command", NamedTextColor.RED))
                return@setDefaultExecutor
            }

            Server.setSpawnPoint(player.position)
            player.sendMessage(Component.text("World spawn set to ${Server.spawnPoint}"))
        }
    }

    private companion object {
        const val PERMISSION = "server.setspawn"
    }
}
