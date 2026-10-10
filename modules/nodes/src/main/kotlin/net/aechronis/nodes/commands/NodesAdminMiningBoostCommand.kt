package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.objects.MiningBoostManager
import net.aechronis.nodes.objects.NodesCommand
import net.minestom.server.command.builder.arguments.ArgumentType

class NodesAdminMiningBoostCommand : NodesCommand("miningboost", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nda miningboost <haste|boost> <multiplier> <time>")
            Message.print(player, "Time accepts seconds by default, or ms, s, m, h, and d suffixes (for example: 30m)")
        }

        val typeArg = ArgumentType.Word("type").from("haste", "boost")
        val multiplierArg = ArgumentType.Integer("multiplier")
        val timeArg = ArgumentType.Word("time")

        addSyntax({ player, resident, context ->
            val duration = parseMiningBoostDuration(context[timeArg])
            if (duration == null) {
                Message.error(player, "Invalid time. Use a positive number of seconds, or add ms, s, m, h, or d (for example: 30m)")
                return@addSyntax
            }

            MiningBoostManager.addBoost(context[typeArg], context[multiplierArg], duration)
                .onSuccess { boost ->
                    Message.print(
                        player,
                        "Global ${context[typeArg]} ${boost.multiplier}x activated for ${formatMiningBoostDuration(boost.remainingMillis)}",
                    )
                }
                .onFailure { error -> Message.error(player, error.message ?: "Failed to activate mining boost") }
        }, typeArg, multiplierArg, timeArg)
    }
}

private fun parseMiningBoostDuration(input: String): Long? {
    val match = Regex("^(\\d+)(ms|s|m|h|d)?$", RegexOption.IGNORE_CASE).matchEntire(input) ?: return null
    val amount = match.groupValues[1].toLongOrNull() ?: return null
    if (amount <= 0L) return null
    val multiplier = when (match.groupValues[2].lowercase()) {
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
