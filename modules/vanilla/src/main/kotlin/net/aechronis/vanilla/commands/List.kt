package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player

class List : Command("list", "vanilla.list", "list") {
    init {
        setDefaultExecutor { sender: Player, _ ->
            val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers
            val message =
                "${onlinePlayers.size} online: " +
                    onlinePlayers.joinToString(", ") { it.username }

            sender.sendMessage(Component.text(message, NamedTextColor.LIGHT_PURPLE))
        }
    }
}
