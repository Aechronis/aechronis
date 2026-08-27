package net.aechronis.vanilla.commands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player

object PlayerTargets {
    fun argument(name: String = "player") =
        ArgumentType.Word(name).setSuggestionCallback { _, _, suggestion ->
            addOnlinePlayerSuggestions(suggestion.getInput()) { entry -> suggestion.addEntry(SuggestionEntry(entry)) }
        }

    fun arguments(name: String = "players") =
        ArgumentType.StringArray(name).setSuggestionCallback { _, _, suggestion ->
            addOnlinePlayerSuggestions(suggestion.getInput()) { entry -> suggestion.addEntry(SuggestionEntry(entry)) }
        }

    internal fun typedToken(input: String): String = input.substringAfterLast(" ").trimEnd('\u0000')

    internal fun suggestions(
        prefix: String,
        playerNames: Collection<String>,
    ): kotlin.collections.List<String> =
        playerNames
            .asSequence()
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .toList()

    internal fun resolve(
        target: String,
        players: Collection<Player>,
    ): kotlin.collections.List<Player>? = players.firstOrNull { it.username.equals(target, ignoreCase = true) }?.let(::listOf)

    private fun addOnlinePlayerSuggestions(
        input: String,
        addSuggestion: (String) -> Unit,
    ) {
        val playerNames = MinecraftServer.getConnectionManager().onlinePlayers.map(Player::getUsername)
        suggestions(typedToken(input), playerNames).forEach(addSuggestion)
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
