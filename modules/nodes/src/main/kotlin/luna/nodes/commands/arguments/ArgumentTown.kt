package luna.nodes.commands.arguments

import luna.nodes.Nodes
import luna.nodes.objects.Town
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentTown {
    /**
     * Creates an argument that autocompletes and returns a Town object.
     */
    fun create(id: String): Argument<Town> {
        val word = ArgumentType.Word(id)
        word.setSuggestionCallback { sender, context, suggestion ->
            val input = suggestion.input.substringAfterLast(" ").lowercase()

            Nodes.towns.values
                .filter { it.name.lowercase().startsWith(input) }
                .forEach { town ->
                    suggestion.addEntry(SuggestionEntry(town.name))
                }
        }
        return word.map { input ->
            Nodes.getTownFromName(input)
                ?: throw ArgumentSyntaxException("Town not found", input, 1)
        }
    }
}