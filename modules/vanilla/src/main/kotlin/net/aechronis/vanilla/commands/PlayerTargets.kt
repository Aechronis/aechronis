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
            val prefix = typedToken(suggestion.getInput())
            val playerNames = MinecraftServer.getConnectionManager().onlinePlayers.map(Player::getUsername)

            suggestions(prefix, playerNames).forEach { suggestion.addEntry(SuggestionEntry(it)) }
        }

    internal fun typedToken(input: String): String = input.substringAfterLast(" ")

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

    internal fun resolve(
        target: String,
        players: Collection<Player>,
    ): kotlin.collections.List<Player>? {
        if (target == "*") return players.toList()

        return players.firstOrNull { it.username.equals(target, ignoreCase = true) }?.let(::listOf)
    }

    fun resolve(
        sender: Player,
        target: String,
    ): kotlin.collections.List<Player>? =
        resolve(target, MinecraftServer.getConnectionManager().onlinePlayers)
            ?: run {
                sender.sendMessage(Component.text("Player not found: $target", NamedTextColor.RED))
                null
            }
}
