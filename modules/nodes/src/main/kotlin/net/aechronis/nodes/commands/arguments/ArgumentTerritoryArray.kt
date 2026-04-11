package net.aechronis.nodes.commands.arguments

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryId
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentTerritoryArray {
    /**
     * Creates an argument that accepts multiple territory IDs and returns a list of Territory objects.
     */
    fun create(id: String): Argument<List<Territory>> {
        val stringArray = ArgumentType.StringArray(id)
        stringArray.setSuggestionCallback { sender, context, suggestion ->
            val input = suggestion.input.substringAfterLast(" ").lowercase()

            Nodes.territories.values
                .filter { it.id.toString().startsWith(input) }
                .forEach { territory ->
                    suggestion.addEntry(SuggestionEntry(territory.id.toString()))
                }
        }
        return stringArray.map { inputs ->
            inputs.map { input ->
                val territoryId = input.toIntOrNull()
                    ?: throw ArgumentSyntaxException("Invalid territory ID: $input", input, 1)

                Nodes.getTerritoryFromId(TerritoryId(territoryId))
                    ?: throw ArgumentSyntaxException("Territory not found: $input", input, 2)
            }
        }
    }
}
