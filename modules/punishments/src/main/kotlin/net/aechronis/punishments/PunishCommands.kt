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
import net.minestom.server.entity.Player
import java.text.SimpleDateFormat
import java.util.Date

private val target = PlayerTargets.argument("player")
private val reasonArg = ArgumentType.StringArray("reason")
private val infractionArg: ArgumentEnum<Infraction> =
    ArgumentType.Enum("infraction", Infraction::class.java).setFormat(ArgumentEnum.Format.LOWER_CASED)

private fun CommandContext.reason(fallback: String): String = getOrDefault(reasonArg, null)?.joinToString(" ")?.ifBlank { null } ?: fallback

internal fun CommandSender.staff(): Staff = (this as? Player)?.let { Staff(it.uuid, it.username) } ?: Staff.CONSOLE

internal fun CommandSender.info(text: String) = sendMessage(Component.text(text, NamedTextColor.GOLD))

internal fun CommandSender.fail(text: String) = sendMessage(Component.text(text, NamedTextColor.RED))

/** Runs blocking work off the tick thread. A Discord caller waits for it through [Punishments.commandTasks]. */
internal fun async(block: () -> Unit) {
    val task = ModuleAsync.runAsync { block() }
    Punishments.commandTasks.get()?.add(task)
}

/** Runs blocking database and Mojang work off the tick thread, resolving the target first. */
internal fun CommandSender.withTarget(
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

/** `/punish <player>` opens the infraction menu. Jr. mods only see the Chat category. */
class PunishCommand : Command("punish", PunishPerms.CHAT) {
    override fun hasPermission(
        player: Player,
        permission: String?,
    ): Boolean = PunishPerms.canOpenMenu(player.uuid)

    init {
        setDefaultExecutor { player, _ -> player.info("/punish <player>") }
        addSyntax({ player: Player, context -> player.withTarget(context[target]) { PunishMenu.open(player, it) } }, target)
    }
}

class HistoryCommand : Command("history", PunishPerms.HISTORY) {
    init {
        setSenderDefaultExecutor { sender, _ -> sender.info("/history <player>") }
        addSenderSyntax({ sender, context ->
            sender.withTarget(context[target]) { player ->
                val records = Punishments.repository.history(player.uuid)
                sender.info("${player.name} (${player.uuid}): ${records.size} record(s)")
                val strikes = Punishments.strikes(player.uuid)
                if (strikes.isEmpty()) sender.info("  No strikes.")
                strikes.forEach { (infraction, count) -> sender.info("  ${infraction.label}: $count/${infraction.levels.size} strike(s)") }
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm")
                val shown = mutableSetOf<String>()
                records.take(20).forEach { record ->
                    val state =
                        when {
                            record.revokedAt != null -> " [lifted by ${record.revokedBy}]"
                            record.active() -> " [active]"
                            else -> ""
                        }
                    val row = record.categoryLabel?.let { " ($it${record.strike?.let { strike -> " #$strike" }.orEmpty()})" }.orEmpty()
                    val anonymous = if (record.anonymous) " (anon)" else ""
                    sender.sendMessage(
                        Component.text(
                            "  #${record.id} ${format.format(Date(record.createdAt))} ${record.type.label}$row: ${record.reason} " +
                                "- ${record.staffName}$anonymous$state",
                            NamedTextColor.GRAY,
                        ),
                    )
                    // Records of one offence share their evidence, so list it once.
                    if (shown.add(record.offence)) {
                        record.evidence?.lines()?.forEach {
                            sender.sendMessage(Component.text("    Evidence: $it", NamedTextColor.DARK_GRAY))
                        }
                    }
                }
            }
        }, target)
    }
}

/** `/addstrike` and `/removestrike` change a row's strikes without punishing or lifting anything. */
private fun strikeCommand(
    command: Command,
    delta: Int,
) {
    command.setSenderDefaultExecutor { sender, _ -> sender.info("/${command.name} <player> <infraction> [reason]") }
    val handler = { sender: CommandSender, context: CommandContext ->
        val infraction = context[infractionArg]
        sender.withTarget(context[target]) { player ->
            val reason = context.reason(if (delta > 0) "Added by hand" else "Removed by hand")
            val strikes = Punishments.adjustStrikes(player, infraction, delta, sender.staff(), reason)
            sender.info("${player.name}: ${infraction.label} is now $strikes/${infraction.levels.size} strike(s).")
        }
    }
    command.addSenderSyntax(handler, target, infractionArg)
    command.addSenderSyntax(handler, target, infractionArg, reasonArg)
}

class AddStrikeCommand : Command("addstrike", PunishPerms.STRIKES) {
    init {
        strikeCommand(this, 1)
    }
}

class RemoveStrikeCommand : Command("removestrike", PunishPerms.STRIKES) {
    init {
        strikeCommand(this, -1)
    }
}

/** Accounts that joined from one of the player's IPs. The IPs themselves are never shown. */
class AltsCommand : Command("alts", PunishPerms.ALTS) {
    init {
        setSenderDefaultExecutor { sender, _ -> sender.info("/alts <player>") }
        addSenderSyntax({ sender, context ->
            sender.withTarget(context[target]) { player ->
                val alts = Punishments.repository.alts(player.uuid)
                if (alts.isEmpty()) {
                    sender.info("${player.name} shares no IP with another account.")
                    return@withTarget
                }
                sender.info("${player.name} shares an IP with ${alts.size} account(s):")
                alts.forEach { alt ->
                    val active = Punishments.repository.active(alt.uuid)
                    val status =
                        when {
                            active.any { it.type == Type.BLACKLIST } -> " [blacklisted]"
                            active.any { it.type.blocksLogin } -> " [banned]"
                            active.any { it.type == Type.MUTE } -> " [muted]"
                            else -> ""
                        }
                    sender.sendMessage(Component.text("  ${alt.name}$status", NamedTextColor.GRAY))
                }
            }
        }, target)
    }
}

/** Blacklisted groups, by name only; their players aren't tracked. Players are blacklisted through `/punish`. */
class BlacklistCommand : Command("blacklist", PunishPerms.BLACKLIST) {
    init {
        val group = ArgumentType.Literal("group")
        val groupName = ArgumentType.Word("group-name")
        setSenderDefaultExecutor { sender, _ -> sender.info("/blacklist group add <name> [reason] | remove <name>") }

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
        addSenderSyntax(addGroup, group, ArgumentType.Literal("add"), groupName)
        addSenderSyntax(addGroup, group, ArgumentType.Literal("add"), groupName, reasonArg)

        addSenderSyntax({ sender, context ->
            val name = context[groupName]
            async {
                if (Punishments.repository.deleteGroup(name)) {
                    sender.info("Removed group $name from the blacklist.")
                } else {
                    sender.fail("No blacklisted group named $name.")
                }
            }
        }, group, ArgumentType.Literal("remove"), groupName)
    }
}

class NewIterationCommand : Command("newiter", PunishPerms.ADMIN) {
    init {
        setSenderDefaultExecutor { sender, _ ->
            sender.fail(
                "This lifts every iteration ban (and the IP bans issued with them) and takes one strike off every row " +
                    "that doesn't lead to a blacklist. Run /newiter confirm at the start of a new iteration.",
            )
        }
        addSenderSyntax({ sender, _ ->
            async {
                val (lifted, decayed) = Punishments.newIteration(sender.staff())
                val ipBans = lifted.count { it.type == Type.IP_BAN }
                sender.info("Lifted ${lifted.size - ipBans} iteration ban(s) and $ipBans IP ban(s); decayed $decayed strike row(s).")
            }
        }, ArgumentType.Literal("confirm"))
    }
}
