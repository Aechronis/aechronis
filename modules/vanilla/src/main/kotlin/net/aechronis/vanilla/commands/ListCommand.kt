package net.aechronis.vanilla.commands

import net.aechronis.vanilla.utils.Command
import net.aechronis.vanilla.utils.Message
import net.minestom.server.MinecraftServer

class ListCommand : Command("list", "vanilla.list", "list") {
    init {
        setDefaultExecutor { sender, _ ->
            val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers
            val message =
                "${onlinePlayers.size} online: " +
                    onlinePlayers.joinToString(", ") { it.username }

            Message.print(sender, message)
        }
    }
}
