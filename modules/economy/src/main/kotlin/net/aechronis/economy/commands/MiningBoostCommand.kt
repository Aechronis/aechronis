package net.aechronis.economy.commands

import net.aechronis.economy.MiningBoostManager
import net.aechronis.server.CommandOutput
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class MiningBoostCommand : Command("miningboost", "economy.admin") {
    init {
        setDefaultExecutor { player, _ ->
            CommandOutput.send(player, Component.text("Usage: /miningboost <haste|boost> <multiplier> <time>", NamedTextColor.AQUA))
            CommandOutput.send(
                player,
                Component.text("Time accepts seconds by default, or ms, s, m, h, and d suffixes (for example: 30m)", NamedTextColor.AQUA),
            )
        }

        val typeArg = ArgumentType.Word("type").from("haste", "boost")
        val multiplierArg = ArgumentType.Integer("multiplier")
        val timeArg = ArgumentType.Word("time")

        addSyntax({ player: Player, context: CommandContext ->
            val duration = parseMiningBoostDuration(context[timeArg])
            if (duration == null) {
                CommandOutput.send(
                    player,
                    Component.text(
                        "Invalid time. Use a positive number of seconds, or add ms, s, m, h, or d (for example: 30m)",
                        NamedTextColor.RED,
                    ),
                )
                return@addSyntax
            }

            MiningBoostManager
                .addBoost(context[typeArg], context[multiplierArg], duration)
                .onSuccess { boost ->
                    CommandOutput.send(
                        player,
                        Component.text(
                            "Global ${context[typeArg]} ${boost.multiplier}x activated for ${formatMiningBoostDuration(
                                boost.remainingMillis,
                            )}",
                            NamedTextColor.AQUA,
                        ),
                    )
                }.onFailure { error ->
                    CommandOutput.send(player, Component.text(error.message ?: "Failed to activate mining boost", NamedTextColor.RED))
                }
        }, typeArg, multiplierArg, timeArg)
    }
}

private fun parseMiningBoostDuration(input: String): Long? {
    val match = Regex("^(\\d+)(ms|s|m|h|d)?$", RegexOption.IGNORE_CASE).matchEntire(input) ?: return null
    val amount = match.groupValues[1].toLongOrNull() ?: return null
    if (amount <= 0L) return null
    val multiplier =
        when (match.groupValues[2].lowercase()) {
            "ms" -> 1L
            "", "s" -> 1_000L
            "m" -> 60_000L
            "h" -> 3_600_000L
            "d" -> 86_400_000L
            else -> return null
        }
    return runCatching { Math.multiplyExact(amount, multiplier) }.getOrNull()
}

private fun formatMiningBoostDuration(milliseconds: Long): String {
    val seconds = (milliseconds + 999L) / 1000L
    return when {
        seconds >= 86_400L -> "${seconds / 86_400L}d"
        seconds >= 3_600L -> "${seconds / 3_600L}h"
        seconds >= 60L -> "${seconds / 60L}m"
        else -> "${seconds}s"
    }
}
