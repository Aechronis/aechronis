package net.aechronis.nodes.commands.arguments

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.ResourceNode
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentResourceNode {
    fun create(id: String): Argument<ResourceNode> {
        val string = ArgumentType.String(id)
        string.setSuggestionCallback { _, _, suggestion ->
            val input = suggestion.input
            val start = suggestion.start.coerceIn(0, input.length)
            val end = (start + suggestion.length).coerceIn(start, input.length)
            val prefix = input.substring(start, end).removePrefix("\"").lowercase()

            Nodes.resourceNodes.keys
                .asSequence()
                .filter { it.lowercase().startsWith(prefix) }
                .sortedBy { it.lowercase() }
                .forEach { name ->
                    val suggestedName = if (name.any(Char::isWhitespace)) {
                        "\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                    } else {
                        name
                    }
                    suggestion.addEntry(SuggestionEntry(suggestedName))
                }
        }
        return string.map { input ->
            Nodes.resourceNodes.entries
                .firstOrNull { (name, _) -> name.equals(input, ignoreCase = true) }
                ?.value
                ?: throw ArgumentSyntaxException("Resource node not found", input, 1)
        }
    }
}
