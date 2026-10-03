package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.commands.arguments.ArgumentTerritory
import net.aechronis.nodes.commands.arguments.ArgumentTerritoryArray
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.Warzone
import net.minestom.server.command.builder.arguments.ArgumentType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class WarzoneCommand : NodesCommand("warzone") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /warzone <territory-id>")
        }

        val territoryArg = ArgumentTerritory.create("territory-id")
        addSyntax({ player, _, context ->
            val territory = context[territoryArg]
            val summary = Warzone.summary(territory)
            if (summary == null) {
                Message.print(player, "Territory ${territory.id} is not a warzone")
                return@addSyntax
            }
            val now = System.currentTimeMillis()
            Message.print(player, "${ChatColor.BOLD}Warzone rankings for territory ${territory.id}:")
            Message.print(player, describeWindow(summary, now))
            val ranking = Warzone.ranking(territory, now)
            if (ranking.isEmpty()) {
                Message.print(player, "- No nation has held this territory yet")
            } else {
                ranking.forEachIndexed { index, score ->
                    Message.print(player, "${index + 1}. ${score.nation.name}${ChatColor.WHITE}: ${Warzone.formatTime(score.millis)}")
                }
            }
        }, territoryArg)
    }
}

class NodesAdminWarzoneCommand : NodesCommand("warzone", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nda warzone create <start> <duration> <territory-ids>")
            Message.print(player, "  start: now, a delay like 2h or 1d6h, or a time like 2026-10-04T18:00 (${zone().id})")
            Message.print(player, "  duration: like 90m, 2h or 1d12h")
            Message.print(player, "/nda warzone list")
            Message.print(player, "/nda warzone stop <territory-id> (end now and award)")
            Message.print(player, "/nda warzone cancel <territory-id> (remove without award)")
        }

        addSubcommand(NodesAdminWarzoneCreateCommand())
        addSubcommand(NodesAdminWarzoneListCommand())
        addSubcommand(NodesAdminWarzoneStopCommand())
        addSubcommand(NodesAdminWarzoneCancelCommand())
    }
}

private class NodesAdminWarzoneCreateCommand : NodesCommand("create", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nda warzone create <start> <duration> <territory-ids>")
        }

        val startArg = ArgumentType.Word("start")
        val durationArg = ArgumentType.Word("duration")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")
        addSyntax({ player, _, context ->
            val now = System.currentTimeMillis()
            val start = parseStart(context[startArg], now)
            if (start == null) {
                Message.error(player, "Invalid start. Use now, a delay like 2h, or a time like 2026-10-04T18:00 (${zone().id})")
                return@addSyntax
            }
            val duration = parseDuration(context[durationArg])
            if (duration == null) {
                Message.error(player, "Invalid duration. Use something like 90m, 2h or 1d12h")
                return@addSyntax
            }
            val territories = context[territoriesArg].distinctBy { it.id }
            val end = start + duration
            Warzone.schedule(territories, start, end, now)
                .onSuccess {
                    val ids = territories.joinToString(", ") { it.id.toString() }
                    Message.print(
                        player,
                        "Warzone scheduled for territories $ids from ${formatInstant(start)} to ${formatInstant(end)} " +
                            "(starts ${if (start <= now) "now" else "in ${Warzone.formatDuration(start - now)}"})",
                    )
                }
                .onFailure { error -> Message.error(player, error.message ?: "Failed to schedule warzone") }
        }, startArg, durationArg, territoriesArg)
    }
}

private class NodesAdminWarzoneListCommand : NodesCommand("list", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            val summaries = Warzone.summaries()
            if (summaries.isEmpty()) {
                Message.print(player, "No warzones are scheduled")
                return@setDefaultExecutor
            }
            val now = System.currentTimeMillis()
            Message.print(player, "${ChatColor.BOLD}Warzones:")
            summaries.forEach { summary ->
                val leader = summary.leader?.let { "${it.nation.name} ${Warzone.formatTime(it.millis)}" } ?: "no holder yet"
                Message.print(player, "- ${summary.territoryId}: ${describeWindow(summary, now)}; leader: $leader")
            }
        }
    }
}

private class NodesAdminWarzoneStopCommand : NodesCommand("stop", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nda warzone stop <territory-id>")
        }

        val territoryArg = ArgumentTerritory.create("territory-id")
        addSyntax({ player, _, context ->
            Warzone.stop(context[territoryArg])
                .onFailure { error -> Message.error(player, error.message ?: "Failed to stop warzone") }
        }, territoryArg)
    }
}

private class NodesAdminWarzoneCancelCommand : NodesCommand("cancel", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nda warzone cancel <territory-id>")
        }

        val territoryArg = ArgumentTerritory.create("territory-id")
        addSyntax({ player, _, context ->
            val territory = context[territoryArg]
            Warzone.cancel(territory)
                .onSuccess { Message.print(player, "Cancelled warzone for territory ${territory.id}; nothing was awarded") }
                .onFailure { error -> Message.error(player, error.message ?: "Failed to cancel warzone") }
        }, territoryArg)
    }
}

private fun zone(): ZoneId = runCatching { ZoneId.of(Nodes.config.warzoneTimeZone) }.getOrDefault(ZoneId.of("UTC"))

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")

private fun formatInstant(millis: Long): String = timeFormat.format(Instant.ofEpochMilli(millis).atZone(zone()))

private fun describeWindow(summary: Warzone.Summary, now: Long): String {
    val end = summary.endMillis
    return when {
        !summary.started -> "starts in ${Warzone.formatDuration(summary.startMillis - now)} (${formatInstant(summary.startMillis)})" +
            (end?.let { ", ends ${formatInstant(it)}" } ?: "")

        end == null -> "running until an admin stops it"

        else -> "ends in ${Warzone.formatDuration((end - now).coerceAtLeast(0L))} (${formatInstant(end)})"
    }
}

/** `now`, a delay from now such as `2h`, or a local time such as `2026-10-04T18:00`. */
private fun parseStart(input: String, now: Long): Long? {
    if (input.equals("now", ignoreCase = true)) return now
    parseDuration(input)?.let { return now + it }
    return try {
        LocalDateTime.parse(input).atZone(zone()).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

/** A positive duration made of d, h, m and s parts, such as `90m` or `1d12h`. */
private fun parseDuration(input: String): Long? {
    if (!Regex("^(\\d+[dhms])+$", RegexOption.IGNORE_CASE).matches(input)) return null
    var total = 0L
    for (part in Regex("(\\d+)([dhms])", RegexOption.IGNORE_CASE).findAll(input)) {
        val amount = part.groupValues[1].toLongOrNull() ?: return null
        val unit = when (part.groupValues[2].lowercase()) {
            "d" -> 86_400_000L
            "h" -> 3_600_000L
            "m" -> 60_000L
            else -> 1_000L
        }
        total = runCatching { Math.addExact(total, Math.multiplyExact(amount, unit)) }.getOrNull() ?: return null
    }
    return total.takeIf { it > 0L }
}
