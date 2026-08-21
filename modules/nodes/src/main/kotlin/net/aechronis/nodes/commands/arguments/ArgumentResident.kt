package net.aechronis.nodes.commands.arguments

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Resident
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

internal fun matchingResidents(input: String): List<Resident> = Nodes.residents.values
    .asSequence()
    .filter { it.name.startsWith(input, ignoreCase = true) }
    .sortedBy { it.name.lowercase() }
    .toList()

object ArgumentResident {
    /**
     * Creates an argument that autocompletes and returns a Resident object.
     */
    fun create(id: String): Argument<Resident> {
        val word = ArgumentType.Word(id)
        word.setSuggestionCallback { _, _, suggestion ->
            val input = suggestion.input.substringAfterLast(" ")
            matchingResidents(input).forEach { resident ->
                suggestion.addEntry(SuggestionEntry(resident.name))
            }
        }
        return word.map { input ->
            Resident.fromName(input)
                ?: throw ArgumentSyntaxException("Resident not found", input, 1)
        }
    }
}
