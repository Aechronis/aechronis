package luna.nodes.commands.arguments

import luna.nodes.Nodes
import luna.nodes.objects.Port
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentPortArray {
    /**
     * Creates an argument that accepts multiple ports and returns a list of Port objects.
     */
    fun create(id: String): Argument<List<Port>> {
        val stringArray = ArgumentType.StringArray(id)
        stringArray.setSuggestionCallback { sender, context, suggestion ->
            val input = suggestion.input.substringAfterLast(" ").lowercase()

            Nodes.ports.values
                .filter { it.name.lowercase().startsWith(input) }
                .forEach { port ->
                    suggestion.addEntry(SuggestionEntry(port.name))
                }
        }
        return stringArray.map { inputs ->
            inputs.map { input ->
                Nodes.getPortFromName(input)
                    ?: throw ArgumentSyntaxException("Port not found", input, 1)
            }
        }
    }
}
