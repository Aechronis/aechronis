package net.aechronis.punishments

import net.aechronis.server.modules.ModuleAsync
import net.aechronis.utils.Command
import net.aechronis.vanilla.commands.PlayerTargets
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.arguments.ArgumentEnum
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionCallback
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date

private val target = PlayerTargets.argument("player")
private val reasonArg = ArgumentType.StringArray("reason")

private fun CommandContext.reason(fallback: String): String = getOrDefault(reasonArg, null)?.joinToString(" ")?.ifBlank { null } ?: fallback

private fun CommandSender.staff(): Staff = (this as? Player)?.let { Staff(it.uuid, it.username) } ?: Staff.CONSOLE

private fun CommandSender.info(text: String) = sendMessage(Component.text(text, NamedTextColor.GOLD))

private fun CommandSender.fail(text: String) = sendMessage(Component.text(text, NamedTextColor.RED))

/** Runs blocking work off the tick thread. A Discord caller waits for it through [Punishments.commandTasks]. */
internal fun async(block: () -> Unit) {
    val task = ModuleAsync.runAsync { block() }
    Punishments.commandTasks.get()?.add(task)
}

/** Runs blocking database and Mojang work off the tick thread, resolving the target first. */
private fun CommandSender.withTarget(
    query: String,
    action: (KnownPlayer) -> Unit,
) {
    async {
        try {
            val player = Punishments.resolve(query)
            if (player == null) fail("Unknown player: $query") else action(player)
        } catch (error: Exception) {
            fail("Punishment failed: ${error.message}")
            System.err.println("[Punishments] Command failed: $error")
        }
    }
}

private fun CommandSender.report(
    player: KnownPlayer,
    records: List<Record>,
) {
    if (records.isEmpty()) {
        fail("Nothing was applied to ${player.name}.")
        return
    }
    val applied =
        records.joinToString(" + ") { record ->
            Action(record.type, record.expiresAt?.let { Duration.ofMillis(it - record.createdAt) }).describe()
        }
    info("${player.name}: $applied (${records.first().reason})")
    Evidence.offer(this, records)
}

private fun CommandSender.reportLifted(
    player: KnownPlayer,
    records: List<Record>,
    what: String,
) = if (records.isEmpty()) {
    fail(
        "${player.name} has no active $what.",
    )
} else {
    info("Lifted ${records.size} $what record(s) for ${player.name}.")
}

private val categoryArg: ArgumentEnum<Category> =
    ArgumentType.Enum("category", Category::class.java).setFormat(ArgumentEnum.Format.LOWER_CASED)

/**
 * Tab-complete for the type and time of `/punish`, chosen by word position: Minestom can hand a later word to an
 * earlier argument's callback. The guideline's pick comes first, marked recommended.
 */
private val punishSuggestions =
    SuggestionCallback { _, _, suggestion ->
        val words =
            suggestion.input
                .trimStart('/')
                .trimEnd('\u0000')
                .split(' ')
        val category = Category.entries.firstOrNull { it.name.equals(words.getOrNull(2), ignoreCase = true) } ?: return@SuggestionCallback
        val type = words.getOrNull(3)?.lowercase()
        val timed = type == "mute" || type == "ban"
        val options =
            when (words.size) {
                4 -> PUNISHMENT_TYPES
                5 -> if (timed) PUNISHMENT_DURATIONS.filter { type == "ban" || it != "iteration" } else return@SuggestionCallback
                else -> return@SuggestionCallback
            }
        val pick =
            runCatching { category.next(Punishments.offences(words[1], category)).firstNotNullOfOrNull { it.choice() } }.getOrNull()
        val recommended = if (words.size == 4) pick?.first else pick?.takeIf { it.first == type }?.second
        (listOfNotNull(recommended) + options).distinct().filter { it.startsWith(words.last(), ignoreCase = true) }.forEach {
            suggestion.addEntry(if (it == recommended) SuggestionEntry(it, Component.text("Recommended")) else SuggestionEntry(it))
        }
    }

/** The punishment for a `/punish` type and length, or why there isn't one. */
private fun punishment(
    type: String,
    duration: String?,
): Result<Action> {
    fun fail(message: String) = Result.failure<Action>(IllegalArgumentException(message))
    return when (type) {
        "warn", "blacklist" ->
            if (duration != null) fail("A $type doesn't take a time.") else Result.success(Action(Type.valueOf(type.uppercase())))
        "mute", "ban" ->
            when {
                duration == null ->
                    fail(
                        "Pick a time: ${PUNISHMENT_DURATIONS.filter { type == "ban" || it != "iteration" }.joinToString()}",
                    )
                duration == "iteration" && type == "mute" -> fail("Only bans can last the iteration.")
                duration == "iteration" -> Result.success(Action(Type.ITERATION_BAN))
                else ->
                    parseDuration(duration)?.let { Result.success(Action(Type.valueOf(type.uppercase()), it.takeIf { !it.isZero })) }
                        ?: fail("Unknown time: $duration")
            }
        else -> fail("Pick a type: ${PUNISHMENT_TYPES.joinToString()}")
    }
}

class PunishCommand : Command("punish", "punishments.punish") {
    init {
        val usage = "/punish <player> <category> <warn|mute|ban|blacklist> [time] [reason]"
        val typeArg = ArgumentType.Word("type").setSuggestionCallback(punishSuggestions)
        val durationArg = ArgumentType.Word("time").setSuggestionCallback(punishSuggestions)
        val punishReason = ArgumentType.StringArray("reason").setSuggestionCallback(punishSuggestions)
        setSenderDefaultExecutor { sender, _ ->
            sender.info(usage)
            sender.info("Categories: ${Category.entries.joinToString { it.name.lowercase() }}")
        }
        // Without a type, show the guideline's recommendation.
        addSenderSyntax({ sender, context ->
            val category = context[categoryArg]
            async {
                val prior = Punishments.offences(context[target], category)
                sender.info("${context[target]} has $prior earlier ${category.label.lowercase()} offence(s).")
                sender.info("Recommended: ${Punishments.recommendation(category, prior)}")
                sender.info(usage)
            }
        }, target, categoryArg)

        // The greedy reason syntax can win over the time one, so also take the time from the reason's first word.
        val run: (CommandSender, CommandContext) -> Unit = { sender, context ->
            val category = context[categoryArg]
            val type = context[typeArg].lowercase()
            val words = context.getOrDefault(punishReason, null)?.toMutableList() ?: mutableListOf()
            val leading = words.firstOrNull()?.lowercase()
            val isTime = leading == "iteration" || leading?.let(::parseDuration) != null
            // A time on a warning or blacklist is rejected below rather than kept as the reason.
            val duration = context.getOrDefault(durationArg, null)?.lowercase() ?: leading.takeIf { isTime }?.also { words.removeAt(0) }
            punishment(type, duration).fold(
                onSuccess = { action ->
                    val reason = words.joinToString(" ").ifBlank { category.label }
                    sender.withTarget(context[target]) { player ->
                        // Ban evasion pairs the iteration ban with an IP ban in one offence, so /liftiterationbans lifts both.
                        val evasion = category == Category.BAN_EVASION && action.type == Type.ITERATION_BAN && player.lastIp != null
                        val actions = if (evasion) listOf(action, Action(Type.IP_BAN)) else listOf(action)
                        sender.report(player, Punishments.apply(player, actions, category, reason, sender.staff()))
                    }
                },
                onFailure = { sender.fail(it.message ?: usage) },
            )
        }
        addSenderSyntax(run, target, categoryArg, typeArg)
        addSenderSyntax(run, target, categoryArg, typeArg, punishReason)
        addSenderSyntax(run, target, categoryArg, typeArg, durationArg)
        addSenderSyntax(run, target, categoryArg, typeArg, durationArg, punishReason)
    }
}

class WarnCommand : Command("warn", "punishments.warn") {
    init {
        setSenderDefaultExecutor { sender, _ -> sender.info("/warn <player> <reason>") }
        addSenderSyntax({ sender, context ->
            sender.withTarget(context[target]) { player ->
                sender.report(player, Punishments.apply(player, listOf(Action(Type.WARN)), null, context.reason(""), sender.staff()))
            }
        }, target, reasonArg)
    }
}

/** `/mute` and `/ban` take a duration like `7d` or `perm`; `/ban` also takes `iteration`. */
private fun timedCommand(
    command: Command,
    type: Type,
) {
    val usage = "/${command.name} <player> <time|perm${if (type == Type.BAN) "|iteration" else ""}> [reason]"
    command.setSenderDefaultExecutor { sender, _ -> sender.info(usage) }
    val durations = PUNISHMENT_DURATIONS.filter { type == Type.BAN || it != "iteration" }
    val durationArg =
        ArgumentType.Word("time").setSuggestionCallback { _, _, suggestion ->
            durations.forEach { suggestion.addEntry(SuggestionEntry(it)) }
        }
    val handler = { sender: CommandSender, context: CommandContext ->
        val input = context[durationArg]
        val action =
            if (type == Type.BAN && input.equals("iteration", true)) {
                Action(Type.ITERATION_BAN)
            } else {
                parseDuration(input)?.let { Action(type, it.takeIf { duration -> !duration.isZero }) }
            }
        if (action == null) {
            sender.fail(usage)
        } else {
            sender.withTarget(context[target]) { player ->
                sender.report(player, Punishments.apply(player, listOf(action), null, context.reason(action.describe()), sender.staff()))
            }
        }
    }
    command.addSenderSyntax(handler, target, durationArg)
    command.addSenderSyntax(handler, target, durationArg, reasonArg)
}

class MuteCommand : Command("mute", "punishments.mute") {
    init {
        timedCommand(this, Type.MUTE)
    }
}

class BanCommand : Command("ban", "punishments.ban") {
    init {
        timedCommand(this, Type.BAN)
    }
}

class IpBanCommand : Command("ipban", "punishments.ban") {
    init {
        setSenderDefaultExecutor { sender, _ -> sender.info("/ipban <player> [reason]") }
        val handler = { sender: CommandSender, context: CommandContext ->
            sender.withTarget(context[target]) { player ->
                if (player.lastIp == null) {
                    sender.fail("${player.name} has never joined, so there is no IP to ban.")
                } else {
                    sender.report(
                        player,
                        Punishments.apply(player, listOf(Action(Type.IP_BAN)), null, context.reason("IP ban"), sender.staff()),
                    )
                }
            }
        }
        addSenderSyntax(handler, target)
        addSenderSyntax(handler, target, reasonArg)
    }
}

/** Lifts active records of [types]; history is kept and shows who lifted it. */
private fun liftCommand(
    command: Command,
    types: Set<Type>,
    what: String,
) {
    command.setSenderDefaultExecutor { sender, _ -> sender.info("/${command.name} <player> [reason]") }
    val handler = { sender: CommandSender, context: CommandContext ->
        sender.withTarget(context[target]) { player ->
            sender.reportLifted(player, Punishments.lift(player, types, sender.staff(), context.reason("Lifted")), what)
        }
    }
    command.addSenderSyntax(handler, target)
    command.addSenderSyntax(handler, target, reasonArg)
}

class UnbanCommand : Command("unban", "punishments.ban") {
    init {
        liftCommand(this, setOf(Type.BAN, Type.ITERATION_BAN, Type.IP_BAN), "ban")
    }
}

class UnmuteCommand : Command("unmute", "punishments.mute") {
    init {
        liftCommand(this, setOf(Type.MUTE), "mute")
    }
}

class HistoryCommand : Command("history", "punishments.history") {
    init {
        setSenderDefaultExecutor { sender, _ -> sender.info("/history <player>") }
        addSenderSyntax({ sender, context ->
            sender.withTarget(context[target]) { player ->
                val records = Punishments.repository.history(player.uuid)
                sender.info("${player.name} (${player.uuid}): ${records.size} record(s)")
                Category.entries.forEach { category ->
                    val count = Punishments.repository.offences(player.uuid, category)
                    if (count > 0) sender.info("  ${category.label}: $count offence(s)")
                }
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm")
                val shown = mutableSetOf<String>()
                records.take(20).forEach { record ->
                    val state =
                        when {
                            record.revokedAt != null -> " [lifted by ${record.revokedBy}]"
                            record.active() -> " [active]"
                            else -> ""
                        }
                    sender.sendMessage(
                        Component.text(
                            "  #${record.id} ${format.format(Date(record.createdAt))} ${record.type.label}: ${record.reason} " +
                                "- ${record.staffName}$state",
                            NamedTextColor.GRAY,
                        ),
                    )
                    // Records of one offence share their evidence, so list it once.
                    if (shown.add(record.offence)) {
                        record.evidence?.lines()?.forEach {
                            sender.sendMessage(
                                Component.text("    Evidence: $it", NamedTextColor.DARK_GRAY),
                            )
                        }
                    }
                }
            }
        }, target)
    }
}

class BlacklistCommand : Command("blacklist", "punishments.blacklist") {
    init {
        val add = ArgumentType.Literal("add")
        val remove = ArgumentType.Literal("remove")
        val group = ArgumentType.Literal("group")
        val groupName = ArgumentType.Word("group-name")
        setSenderDefaultExecutor { sender, _ ->
            sender.info("/blacklist add|remove <player> [reason]")
            sender.info("/blacklist group add <name> [reason] | remove <name>")
        }

        val addPlayer = { sender: CommandSender, context: CommandContext ->
            sender.withTarget(context[target]) { player ->
                if (Punishments.repository.active(player.uuid).any { it.type == Type.BLACKLIST }) {
                    sender.fail("${player.name} is already blacklisted.")
                } else {
                    sender.report(
                        player,
                        Punishments.apply(player, listOf(Action(Type.BLACKLIST)), null, context.reason("Blacklisted"), sender.staff()),
                    )
                }
            }
        }
        addSenderSyntax(addPlayer, add, target)
        addSenderSyntax(addPlayer, add, target, reasonArg)

        val removePlayer = { sender: CommandSender, context: CommandContext ->
            sender.withTarget(context[target]) { player ->
                sender.reportLifted(
                    player,
                    Punishments.lift(player, setOf(Type.BLACKLIST), sender.staff(), context.reason("Removed from blacklist")),
                    "blacklist",
                )
            }
        }
        addSenderSyntax(removePlayer, remove, target)
        addSenderSyntax(removePlayer, remove, target, reasonArg)

        // Groups are blacklisted by name only; their players aren't tracked.
        val addGroup = { sender: CommandSender, context: CommandContext ->
            val name = context[groupName]
            val reason = context.reason("")
            async {
                if (Punishments.repository.createGroup(name, reason, sender.staff().name)) {
                    sender.info("Blacklisted group $name.")
                } else {
                    sender.fail("Group $name is already blacklisted.")
                }
            }
            Unit
        }
        addSenderSyntax(addGroup, group, add, groupName)
        addSenderSyntax(addGroup, group, add, groupName, reasonArg)

        addSenderSyntax({ sender, context ->
            val name = context[groupName]
            async {
                if (Punishments.repository.deleteGroup(name)) {
                    sender.info("Removed group $name from the blacklist.")
                } else {
                    sender.fail("No blacklisted group named $name.")
                }
            }
        }, group, remove, groupName)
    }
}

class LiftIterationBansCommand : Command("liftiterationbans", "punishments.admin") {
    init {
        setSenderDefaultExecutor { sender, _ ->
            sender.fail(
                "This lifts every active iteration ban and the IP bans issued with them. " +
                    "Run /liftiterationbans confirm at the start of a new iteration.",
            )
        }
        addSenderSyntax({ sender, _ ->
            async {
                val lifted = Punishments.liftIterationBans(sender.staff(), "New iteration")
                val ipBans = lifted.count { it.type == Type.IP_BAN }
                sender.info("Lifted ${lifted.size - ipBans} iteration ban(s) and $ipBans IP ban(s).")
            }
        }, ArgumentType.Literal("confirm"))
    }
}
