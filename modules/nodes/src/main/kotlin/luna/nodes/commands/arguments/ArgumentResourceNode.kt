package luna.nodes.commands.arguments

import luna.nodes.Nodes
import luna.nodes.objects.ResourceNode
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentResourceNode {
    /**
     * Creates an argument that autocompletes and returns a ResourceNode object.
     */
    fun create(id: String): Argument<ResourceNode> {
        val word = ArgumentType.Word(id)
        word.setSuggestionCallback { sender, context, suggestion ->
            val input = suggestion.input.substringAfterLast(" ").lowercase()

            Nodes.resourceNodes.values
                .filter { it.name.lowercase().startsWith(input) }
                .forEach { resourceNode ->
                    suggestion.addEntry(SuggestionEntry(resourceNode.name))
                }
        }
        return word.map { input ->
            Nodes.resourceNodes.get(input)
                ?: throw ArgumentSyntaxException("ResourceNode not found", input, 1)
        }
    }
}