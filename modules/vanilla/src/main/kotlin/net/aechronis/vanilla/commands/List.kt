package net.aechronis.vanilla.commands

import net.aechronis.vanilla.utils.Command
import net.aechronis.vanilla.utils.Message
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player

class List : Command("list", "vanilla.list", "list") {
    init {
        setDefaultExecutor { sender: Player, _ ->
            val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers
            val message =
                "${onlinePlayers.size} online: " +
                    onlinePlayers.joinToString(", ") { it.username }

            Message.print(sender, message)
        }
    }
}
