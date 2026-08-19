package net.aechronis.vanilla.commands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player

/** Common player-name argument handling, including the vanilla-module '*' target. */
object PlayerTargets {
    fun argument(name: String = "player") =
        ArgumentType.Word(name).setSuggestionCallback { _, _, suggestion ->
            val input = suggestion.getInput()
            val start = suggestion.getStart().coerceIn(0, input.length)
            val end = (start + suggestion.getLength()).coerceIn(start, input.length)
            val prefix = input.substring(start, end)
            val playerNames = MinecraftServer.getConnectionManager().onlinePlayers.map(Player::getUsername)

            suggestions(prefix, playerNames).forEach { suggestion.addEntry(SuggestionEntry(it)) }
        }

    internal fun suggestions(
        prefix: String,
        playerNames: Collection<String>,
    ): kotlin.collections.List<String> =
        buildList {
            if ("*".startsWith(prefix, ignoreCase = true)) add("*")
            addAll(
                playerNames
                    .asSequence()
                    .distinct()
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
                    .filter { it.startsWith(prefix, ignoreCase = true) }
                    .toList(),
            )
        }

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
