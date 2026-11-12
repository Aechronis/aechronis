package net.aechronis.discord

import dev.kord.core.entity.interaction.GroupCommand
import dev.kord.core.entity.interaction.InteractionCommand
import dev.kord.core.entity.interaction.SubCommand
import dev.kord.rest.builder.interaction.BaseInputChatBuilder
import dev.kord.rest.builder.interaction.ChatInputCreateBuilder
import dev.kord.rest.builder.interaction.boolean
import dev.kord.rest.builder.interaction.group
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.interaction.subCommand
import net.aechronis.punishments.Category
import net.aechronis.punishments.PUNISHMENT_DURATIONS
import net.aechronis.punishments.PUNISHMENT_TYPES
import net.aechronis.punishments.Punishments
import net.aechronis.punishments.choice
import net.minestom.server.MinecraftServer

/** Discord options for the punishment commands, turned back into the in-game arguments when run. */
internal object PunishmentOptions {
    private val SINGLE_WORD = listOf("group", "record", "player", "category", "type", "time")

    fun register(
        builder: ChatInputCreateBuilder,
        name: String,
    ) = with(builder) {
        when (name) {
            "punish" -> {
                player()
                string("category", "Guideline category") {
                    required = true
                    Category.entries.forEach { choice(it.label, it.name.lowercase()) }
                }
                string("type", "Warn, mute, ban or blacklist; the guideline's pick is marked") {
                    required = true
                    autocomplete = true
                }
                time(required = false)
                reason()
                evidence()
            }
            "warn" -> {
                player()
                reason(required = true)
                evidence()
            }
            "mute", "ban" -> {
                player()
                time(required = true)
                reason()
                evidence()
            }
            "ipban" -> {
                player()
                reason()
                evidence()
            }
            "unban", "unmute" -> {
                player()
                reason()
            }
            "history" -> player()
            "blacklist" -> {
                subCommand("add", "Blacklist a player") {
                    player()
                    reason()
                    evidence()
                }
                subCommand("remove", "Take a player off the blacklist") {
                    player()
                    reason()
                }
                group("group", "Blacklisted groups") {
                    subCommand("add", "Blacklist a group") {
                        groupName()
                        reason()
                    }
                    subCommand("remove", "Take a group off the blacklist") { groupName() }
                }
            }
            "evidence" -> {
                string("record", "Record number, shown on the log post") { required = true }
                string("link", "Clip link, e.g. a Medal clip from the ticket") {
                    required = true
                    maxLength = 512
                }
            }
            "liftiterationbans" ->
                boolean("confirm", "Lift every iteration ban and the IP bans issued with them") { required = true }
        }
    }

    private fun BaseInputChatBuilder.player() =
        string("player", "Player name or UUID") {
            required = true
            autocomplete = true
        }

    private fun BaseInputChatBuilder.time(required: Boolean) =
        string("time", "1h, 1d, 3d, 1w, 2w, iteration (bans only) or perm") {
            this.required = required
            autocomplete = true
        }

    private fun BaseInputChatBuilder.groupName() = string("group", "Group name") { required = true }

    private fun BaseInputChatBuilder.reason(required: Boolean = false) =
        string("reason", "Reason") {
            this.required = required
            maxLength = 512
        }

    private fun BaseInputChatBuilder.evidence() = string("evidence", "Clip link, e.g. a Medal clip from the ticket") { maxLength = 512 }

    /** Why the options can't be run, if they can't. */
    fun problem(command: InteractionCommand): String? {
        if (SINGLE_WORD.any { command.strings[it]?.any(Char::isWhitespace) == true }) return "Names and durations can't contain spaces."
        val evidence = command.strings["evidence"] ?: command.strings["link"] ?: return null
        val link = evidence.startsWith("https://") || evidence.startsWith("http://")
        return if (link && evidence.none(Char::isWhitespace)) null else "Evidence must be one link, like a Medal clip."
    }

    /** The in-game arguments, in the order the commands take them. The evidence link joins the reason. */
    fun arguments(command: InteractionCommand): String {
        val path =
            when (command) {
                is GroupCommand -> listOf(command.groupName, command.name)
                is SubCommand -> listOf(command.name)
                else -> emptyList()
            }
        val reason = listOfNotNull(command.strings["reason"], command.strings["evidence"], command.strings["link"]).joinToString(" ")
        val confirm = "confirm".takeIf { command.booleans["confirm"] == true }
        return (path + SINGLE_WORD.mapNotNull { command.strings[it] } + listOfNotNull(reason.ifBlank { null }, confirm)).joinToString(" ")
    }

    /** Autocomplete as (label, value) pairs; the guideline's pick comes first, marked recommended. Blocking. */
    fun suggest(
        root: String,
        option: String,
        value: String,
        options: Map<String, String>,
    ): List<Pair<String, String>> {
        val typed = value.trim()
        val recommended =
            if (root == "punish") {
                val category = Category.entries.firstOrNull { it.name.lowercase() == options["category"] }
                val player = options["player"]
                if (category == null || player.isNullOrBlank()) {
                    null
                } else {
                    category.next(Punishments.offences(player, category)).firstNotNullOfOrNull { it.choice() }
                }
            } else {
                null
            }

        fun marked(
            values: List<String>,
            pick: String?,
        ) = (listOfNotNull(pick) + values).distinct().filter { it.startsWith(typed, ignoreCase = true) }.map {
            (if (it == pick) "$it (recommended)" else it) to it
        }
        val durations = PUNISHMENT_DURATIONS.filter { root == "punish" || root == "ban" || it != "iteration" }
        return when (option) {
            "player" -> {
                val online = MinecraftServer.getConnectionManager().onlinePlayers.map { it.username }
                (online.filter { it.startsWith(typed, ignoreCase = true) } + Punishments.names(typed)).distinct().map { it to it }
            }
            "type" -> marked(PUNISHMENT_TYPES, recommended?.first)
            "time" -> {
                val pick = recommended?.takeIf { it.first == options["type"] }?.second
                // Any time like 5d works too, so keep what was typed.
                marked(durations, pick).ifEmpty { listOfNotNull(typed.ifEmpty { null }?.let { it to it }) }
            }
            else -> emptyList()
        }.take(25)
    }
}
