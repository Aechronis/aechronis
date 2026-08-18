package net.aechronis.vanilla.commands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

/** Common player-name argument handling, including the vanilla-module '*' target. */
object PlayerTargets {
    fun argument(name: String = "player") = ArgumentType.Word(name)

    fun resolve(
        sender: Player,
        target: String,
    ): kotlin.collections.List<Player>? {
        if (target == "*") return MinecraftServer.getConnectionManager().onlinePlayers.toList()

        val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(target)
        if (player == null) {
            sender.sendMessage(Component.text("Player not found: $target", NamedTextColor.RED))
            return null
        }
        return listOf(player)
    }
}
