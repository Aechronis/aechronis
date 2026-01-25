package luna.nodes.commands.arguments

import luna.nodes.Nodes
import luna.nodes.objects.PortGroup
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentPortGroup {
    /**
     * Creates an argument that autocompletes and returns a PortGroup object.
     */
    fun create(id: String): Argument<PortGroup> {
        val word = ArgumentType.Word(id)
        word.setSuggestionCallback { sender, context, suggestion ->
            val input = suggestion.input.substringAfterLast(" ").lowercase()

            Nodes.portGroups.values
                .filter { it.name.lowercase().startsWith(input) }
                .forEach { portGroup ->
                    suggestion.addEntry(SuggestionEntry(portGroup.name))
                }
        }
        return word.map { input ->
            Nodes.getPortGroupFromName(input)
                ?: throw ArgumentSyntaxException("Port group not found", input, 1)
        }
    }
}
