package net.aechronis.economy.commands

import net.aechronis.economy.Economy
import net.aechronis.economy.GemRepository
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import java.util.UUID

class BalanceCommand(
    private val repository: GemRepository,
) : Command("balance", null, "bal", "coins", "gems") {
    init {
        val name = ArgumentType.Word("name")
        name.setSuggestionCallback { _, _, suggestion ->
            val prefix = suggestion.input.substringAfterLast(' ')
            val names =
                repository.playersMatching(prefix).map { it.name } +
                    MinecraftServer.getConnectionManager().onlinePlayers.map { it.username }
            names
                .filter { it.startsWith(prefix, ignoreCase = true) }
                .distinctBy { it.lowercase() }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .forEach { suggestion.addEntry(SuggestionEntry(it)) }
        }
        setDefaultExecutor { player, _ ->
            showBalance(player, player.uuid, player.username)
        }
        addSenderSyntax({ sender, context ->
            val input = context[name]
            val online =
                MinecraftServer.getConnectionManager().onlinePlayers.firstOrNull { it.username.equals(input, ignoreCase = true) }
            val account = if (online == null) repository.findPlayer(input) else null
            val uuid = online?.uuid ?: account?.uuid
            val playerName = online?.username ?: account?.name
            if (uuid == null || playerName == null) {
                sender.sendMessage(Component.text("Player not found: $input", NamedTextColor.RED))
                return@addSenderSyntax
            }
            showBalance(sender, uuid, playerName)
        }, name)
    }

    private fun showBalance(
        sender: CommandSender,
        uuid: UUID,
        name: String,
    ) {
        val coins = Economy.coinBalance(uuid) ?: 0L
        val gems = repository.balance(uuid) ?: 0L
        sender.sendMessage(Component.text("$name's balance: Coins: $coins | Gems: $gems", NamedTextColor.GREEN))
    }
}
