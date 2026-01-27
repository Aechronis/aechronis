package luna.nodes.nodes.commands.arguments

import luna.nodes.nodes.Nodes
import luna.nodes.nodes.objects.Port
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentPort {
    /**
     * Creates an argument that autocompletes and returns a Port object.
     */
    fun create(id: String): Argument<Port> {
        val word = ArgumentType.Word(id)
        word.setSuggestionCallback { sender, context, suggestion ->
            val input = suggestion.input.substringAfterLast(" ").lowercase()

            Nodes.ports.values
                .filter { it.name.lowercase().startsWith(input) }
                .forEach { port ->
                    suggestion.addEntry(SuggestionEntry(port.name))
                }
        }
        return word.map { input ->
            Nodes.getPortFromName(input)
                ?: throw ArgumentSyntaxException("Port not found", input, 1)
        }
    }
}
