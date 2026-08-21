package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Punish
import net.aechronis.vanilla.managers.PunishmentAction
import net.aechronis.vanilla.managers.PunishmentRecord
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class PunishCommand : Command("punish", "vanilla.punish") {
    private val playerArg = targetArgument("player")
    private val templateArg = templateArgument()

    init {
        setDefaultExecutor {
            player: Player,
            _,
            ->
            player.sendMessage(Component.text("Usage: /punish <player> [reason-id]", NamedTextColor.LIGHT_PURPLE))
        }
        addSyntax({ staff: Player, context -> showTemplates(staff, context[playerArg]) }, playerArg)
        addSyntax({ staff: Player, context ->
            val target = context[playerArg]
            val template = context[templateArg]
            Punish.punish(staff, target, template).fold(
                onSuccess = { staff.sendMessage(applied(target, it)) },
                onFailure = { staff.sendMessage(Component.text(it.message ?: "Could not issue punishment.", NamedTextColor.RED)) },
            )
        }, playerArg, templateArg)
    }

    private fun showTemplates(
        staff: Player,
        target: String,
    ) {
        if (Punish.resolvePlayer(target) == null) {
            staff.sendMessage(Component.text("Unknown player: $target", NamedTextColor.RED))
            return
        }
        staff.sendMessage(Component.text("Punishment reasons for $target (click one to apply its next strike):", NamedTextColor.GOLD))
        Punish.templates.forEach { template ->
            staff.sendMessage(
                Component
                    .text("• ${template.id}: ${template.label} [rule ${template.rule}]", NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand("/punish $target ${template.id}"))
                    .hoverEvent(HoverEvent.showText(Component.text("Apply ${template.label}"))),
            )
        }
    }
}

class MuteCommand : TimedPunishmentCommand("mute", PunishmentAction.MUTE, "vanilla.mute")

class BanCommand : TimedPunishmentCommand("ban", PunishmentAction.BAN, "vanilla.ban")

open class TimedPunishmentCommand(
    name: String,
    private val action: PunishmentAction,
    permission: String,
) : Command(name, permission) {
    private val playerArg = targetArgument("player")
    private val reasonAndTimeArg = ArgumentType.StringArray("reason-and-time")

    init {
        setDefaultExecutor {
            player: Player,
            _,
            ->
            player.sendMessage(Component.text("Usage: /$name <player> <reason> <time, e.g. 1d1m>", NamedTextColor.LIGHT_PURPLE))
        }
        addSyntax({ staff: Player, context ->
            val words = context[reasonAndTimeArg]
            if (words.size < 2) {
                staff.sendMessage(Component.text("Include a reason followed by a duration, e.g. repeated spam 1d.", NamedTextColor.RED))
                return@addSyntax
            }
            val duration = Punish.duration(words.last())
            if (duration == null) {
                staff.sendMessage(
                    Component.text("Invalid duration. Use combinations of s, m, h, d, and w (for example 1d1m).", NamedTextColor.RED),
                )
                return@addSyntax
            }
            val target = context[playerArg]
            Punish.manual(staff, target, action, words.dropLast(1).joinToString(" "), duration).fold(
                onSuccess = { staff.sendMessage(applied(target, it)) },
                onFailure = { staff.sendMessage(Component.text(it.message ?: "Could not issue punishment.", NamedTextColor.RED)) },
            )
        }, playerArg, reasonAndTimeArg)
    }
}

class HistoryCommand : Command("history", "vanilla.history") {
    private val playerArg = targetArgument("player")
    private val pageArg = ArgumentType.Integer("page").min(1)

    init {
        setDefaultExecutor {
            player: Player,
            _,
            ->
            player.sendMessage(Component.text("Usage: /history <player> [page]", NamedTextColor.LIGHT_PURPLE))
        }
        addSyntax({ staff: Player, context -> show(staff, context[playerArg], 1) }, playerArg)
        addSyntax({ staff: Player, context -> show(staff, context[playerArg], context[pageArg]) }, playerArg, pageArg)
    }

    private fun show(
        staff: Player,
        name: String,
        page: Int,
    ) {
        val history = Punish.history(name, page)
        if (history == null) {
            staff.sendMessage(Component.text("Unknown player: $name", NamedTextColor.RED))
            return
        }
        val (player, records) = history
        staff.sendMessage(Component.text("Punishment history for ${player.name} — page $page", NamedTextColor.GOLD))
        if (records.isEmpty()) {
            staff.sendMessage(Component.text("No punishments found.", NamedTextColor.GRAY))
            return
        }
        records.forEach { staff.sendMessage(historyLine(it)) }
    }
}

class UnmuteCommand : RevokePunishmentCommand("unmute", PunishmentAction.MUTE, "vanilla.mute")

class UnbanCommand : RevokePunishmentCommand("unban", PunishmentAction.BAN, "vanilla.ban")

open class RevokePunishmentCommand(
    name: String,
    private val action: PunishmentAction,
    permission: String,
) : Command(name, permission) {
    private val playerArg = targetArgument("player")

    init {
        setDefaultExecutor {
            player: Player,
            _,
            ->
            player.sendMessage(Component.text("Usage: /$name <player>", NamedTextColor.LIGHT_PURPLE))
        }
        addSyntax({ staff: Player, context ->
            val name = context[playerArg]
            if (Punish.revoke(staff, name, action)) {
                staff.sendMessage(
                    Component.text("Removed active ${action.name.lowercase()} punishment(s) for $name.", NamedTextColor.GREEN),
                )
            } else {
                staff.sendMessage(Component.text("No active ${action.name.lowercase()} punishment found for $name.", NamedTextColor.RED))
            }
        }, playerArg)
    }
}

private fun targetArgument(name: String) =
    ArgumentType.Word(name).setSuggestionCallback { _, _, suggestion ->
        Punish.namesMatching(suggestion.input.substringAfterLast(" ")).forEach { suggestion.addEntry(SuggestionEntry(it)) }
    }

private fun templateArgument() =
    ArgumentType.Word("reason-id").setSuggestionCallback { _, _, suggestion ->
        Punish.templateIdsMatching(suggestion.input.substringAfterLast(" ")).forEach { suggestion.addEntry(SuggestionEntry(it)) }
    }

private fun applied(
    target: String,
    record: PunishmentRecord,
): Component {
    val suffix = record.expiresAt?.let { " until ${DATE.format(Instant.ofEpochMilli(it))}" } ?: ""
    val strike = record.strike?.let { " (strike $it)" } ?: ""
    return Component.text(
        "Applied ${record.action.name.lowercase()} to $target$strike$suffix. Reason: ${record.reason}",
        NamedTextColor.GREEN,
    )
}

private fun historyLine(record: PunishmentRecord): Component {
    val expiry = record.expiresAt?.let { " → ${DATE.format(Instant.ofEpochMilli(it))}" } ?: ""
    val strike = record.strike?.let { " strike $it" } ?: ""
    val revoked = if (record.revokedAt == null) "" else " [revoked]"
    return Component.text(
        "${DATE.format(
            Instant.ofEpochMilli(record.issuedAt),
        )}: ${record.action}$strike — ${record.reason} by ${record.staffName}$expiry$revoked",
        NamedTextColor.GRAY,
    )
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
