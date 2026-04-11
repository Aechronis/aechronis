package net.aechronis.nodes.commands.arguments

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryId
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentTerritory {
    /**
     * Creates an argument that autocompletes and returns a Territory object.
     */
    fun create(id: String): Argument<Territory> {
        val word = ArgumentType.Word(id)
        word.setSuggestionCallback { sender, context, suggestion ->
            val input = suggestion.input.substringAfterLast(" ").lowercase()

            Nodes.territories.values
                .filter { it.id.toString().startsWith(input) }
                .forEach { territory ->
                    suggestion.addEntry(SuggestionEntry(territory.id.toString()))
                }
        }
        return word.map { input ->
            Nodes.getTerritoryFromId(TerritoryId(input.toInt()))
                ?: throw ArgumentSyntaxException("Territory not found", input, 1)
        }
    }
}
