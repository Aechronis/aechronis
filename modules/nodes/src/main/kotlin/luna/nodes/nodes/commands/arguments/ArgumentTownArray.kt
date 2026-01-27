package luna.nodes.nodes.commands.arguments

import luna.nodes.nodes.Nodes
import luna.nodes.nodes.objects.Town
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentTownArray {
    /**
     * Creates an argument that accepts multiple towns and returns a list of Town objects.
     */
    fun create(id: String): Argument<List<Town>> {
        val stringArray = ArgumentType.StringArray(id)
        stringArray.setSuggestionCallback { sender, context, suggestion ->
            val input = suggestion.input.substringAfterLast(" ").lowercase()

            Nodes.towns.values
                .filter { it.name.startsWith(input) }
                .forEach { town ->
                    suggestion.addEntry(SuggestionEntry(town.name))
                }
        }
        return stringArray.map { inputs ->
            inputs.map { input ->
                Nodes.getTownFromName(input)
                    ?: throw ArgumentSyntaxException("Town not found", input, 1)
            }
        }
    }
}
