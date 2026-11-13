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
import net.aechronis.punishments.Infraction
import net.aechronis.punishments.Punishments
import net.minestom.server.MinecraftServer

/** Discord options for the punishment commands, turned back into the in-game arguments when run. Punishing itself is in-game only. */
internal object PunishmentOptions {
    private val SINGLE_WORD = listOf("group", "record", "player", "infraction")

    fun register(
        builder: ChatInputCreateBuilder,
        name: String,
    ) = with(builder) {
        when (name) {
            "history", "alts" -> player()
            "addstrike", "removestrike" -> {
                player()
                string("infraction", "Infraction sheet row") {
                    required = true
                    Infraction.entries.forEach { choice(it.label, it.name.lowercase()) }
                }
                reason()
            }
            "blacklist" ->
                group("group", "Blacklisted groups") {
                    subCommand("add", "Blacklist a group") {
                        groupName()
                        reason()
                    }
                    subCommand("remove", "Take a group off the blacklist") { groupName() }
                }
            "evidence" -> {
                string("record", "Record number, shown on the log post") { required = true }
                string("link", "Clip link, e.g. a Medal clip from the ticket") {
                    required = true
                    maxLength = 512
                }
            }
            "newiter" ->
                boolean("confirm", "Lift every iteration ban and decay strikes that don't lead to a blacklist") { required = true }
        }
    }

    private fun BaseInputChatBuilder.player() =
        string("player", "Player name or UUID") {
            required = true
            autocomplete = true
        }

    private fun BaseInputChatBuilder.groupName() = string("group", "Group name") { required = true }

    private fun BaseInputChatBuilder.reason() =
        string("reason", "Reason") {
            required = false
            maxLength = 512
        }

    /** Why the options can't be run, if they can't. */
    fun problem(command: InteractionCommand): String? {
        if (SINGLE_WORD.any { command.strings[it]?.any(Char::isWhitespace) == true }) return "Names can't contain spaces."
        val link = command.strings["link"] ?: return null
        val valid = link.startsWith("https://") || link.startsWith("http://")
        return if (valid && link.none(Char::isWhitespace)) null else "Evidence must be one link, like a Medal clip."
    }

    /** The in-game arguments, in the order the commands take them. */
    fun arguments(command: InteractionCommand): String {
        val path =
            when (command) {
                is GroupCommand -> listOf(command.groupName, command.name)
                is SubCommand -> listOf(command.name)
                else -> emptyList()
            }
        val reason = listOfNotNull(command.strings["reason"], command.strings["link"]).joinToString(" ")
        val confirm = "confirm".takeIf { command.booleans["confirm"] == true }
        return (path + SINGLE_WORD.mapNotNull { command.strings[it] } + listOfNotNull(reason.ifBlank { null }, confirm)).joinToString(" ")
    }

    /** Player name autocomplete as (label, value) pairs. Blocking. */
    fun suggest(
        option: String,
        value: String,
    ): List<Pair<String, String>> {
        if (option != "player") return emptyList()
        val typed = value.trim()
        val online = MinecraftServer.getConnectionManager().onlinePlayers.map { it.username }
        return (online.filter { it.startsWith(typed, ignoreCase = true) } + Punishments.names(typed)).distinct().map { it to it }.take(25)
    }
}
