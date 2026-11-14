package net.aechronis.nodes.commands.arguments

import net.aechronis.nodes.objects.Town
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

            Town.all()
                .map { NameArgument.toArg(it.name) }
                .filter { it.lowercase().startsWith(input) }
                .forEach { suggestion.addEntry(SuggestionEntry(it)) }
        }
        return stringArray.map { inputs ->
            inputs.map { input ->
                NameArgument.resolve(input, Town::fromName)
                    ?: throw ArgumentSyntaxException("Town not found", input, 1)
            }
        }
    }
}
