package net.aechronis.nodes.commands.arguments

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Nation
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentNation {
    /**
     * Creates an argument that autocompletes and returns a Nation object.
     */
    fun create(id: String): Argument<Nation> {
        val word = ArgumentType.Word(id)
        word.setSuggestionCallback { sender, context, suggestion ->
            val input = suggestion.input.substringAfterLast(" ").lowercase()

            Nodes.nations.values
                .filter { it.name.lowercase().startsWith(input) }
                .forEach { nation ->
                    suggestion.addEntry(SuggestionEntry(nation.name))
                }
        }
        return word.map { input ->
            Nodes.getNationFromName(input)
                ?: throw ArgumentSyntaxException("Nation not found", input, 1)
        }
    }
}
