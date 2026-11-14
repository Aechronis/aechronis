package net.aechronis.nodes.commands.arguments

import net.aechronis.nodes.objects.Town
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentTown {
    /**
     * Creates an argument that autocompletes and returns a Town object.
     * Spaces in names are typed as underscores: New_Haven.
     */
    fun create(id: String): Argument<Town> {
        val word = ArgumentType.Word(id)
        word.setSuggestionCallback { sender, context, suggestion ->
            val input = suggestion.input.substringAfterLast(" ").lowercase()

            Town.all()
                .map { NameArgument.toArg(it.name) }
                .filter { it.lowercase().startsWith(input) }
                .forEach { suggestion.addEntry(SuggestionEntry(it)) }
        }
        return word.map { input ->
            NameArgument.resolve(input, Town::fromName)
                ?: throw ArgumentSyntaxException("Town not found", input, 1)
        }
    }
}
