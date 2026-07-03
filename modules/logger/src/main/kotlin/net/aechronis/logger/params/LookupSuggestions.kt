package net.aechronis.logger.params

import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.suggestion.Suggestion
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.instance.block.Block

object LookupSuggestions {
    private val actionValues = listOf("break", "place", "interact", "block")
    private val timeHints = listOf("1h", "30m", "7d")
    private val radiusHints = listOf("10")
    private val chunkRadiusHints = listOf("1", "2", "3")
    private const val MAX_ENTRIES = 50

    private val blockIds: List<String> by lazy {
        Block.values().map { it.key().asString().removePrefix("minecraft:") }.sorted()
    }

    fun suggest(
        sender: CommandSender,
        @Suppress("UNUSED_PARAMETER") context: CommandContext,
        suggestion: Suggestion,
    ) {
        val input = suggestion.getInput()
        val token = input.substring(input.lastIndexOf(' ') + 1)

        val colon = token.indexOf(':')
        if (colon < 0) {
            ParamKey.entries
                .flatMap { k -> k.aliases.map { "$it:" to k.desc } }
                .filter { it.first.startsWith(token, ignoreCase = true) }
                .forEach { (text, desc) -> suggestion.addEntry(SuggestionEntry(text, Component.text(desc))) }
            return
        }

        val key = ParamKey.fromKey(token.substring(0, colon)) ?: return
        val keyPart = token.substring(0, colon + 1) // includes the ':'
        val value = token.substring(colon + 1)

        when (key) {
            ParamKey.USER -> {
                addValues(suggestion, keyPart, value, MinecraftServer.getConnectionManager().onlinePlayers.map { it.username })
            }

            ParamKey.ACTION -> {
                addValues(suggestion, keyPart, value, actionValues)
            }

            ParamKey.TIME -> {
                addValues(suggestion, keyPart, value, timeHints)
            }

            ParamKey.RADIUS -> {
                addValues(suggestion, keyPart, value, radiusHints)
            }

            ParamKey.CHUNK_RADIUS -> {
                addValues(suggestion, keyPart, value, chunkRadiusHints)
            }

            ParamKey.INCLUDE, ParamKey.EXCLUDE -> {
                addValues(suggestion, keyPart, value, blockIds)
            }
        }
    }

    private fun addValues(
        suggestion: Suggestion,
        keyPart: String,
        value: String,
        candidates: List<String>,
    ) {
        val lastComma = value.lastIndexOf(',')
        val prefix = if (lastComma < 0) "" else value.substring(0, lastComma + 1)
        val partial = value.substring(lastComma + 1)

        candidates
            .asSequence()
            .filter { it.startsWith(partial, ignoreCase = true) }
            .take(MAX_ENTRIES)
            .forEach { suggestion.addEntry(SuggestionEntry(keyPart + prefix + it)) }
    }
}
