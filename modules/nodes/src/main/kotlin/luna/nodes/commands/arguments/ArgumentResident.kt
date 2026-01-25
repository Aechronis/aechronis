package luna.nodes.commands.arguments

import luna.nodes.Nodes
import luna.nodes.objects.Resident
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentResident {
    /**
     * Creates an argument that autocompletes and returns a Resident object.
     */
    fun create(id: String): Argument<Resident> {
        val word = ArgumentType.Word(id)
        word.setSuggestionCallback { sender, context, suggestion ->
            val input = suggestion.input.substringAfterLast(" ").lowercase()

            Nodes.residents.values
                .filter { it.name.lowercase().startsWith(input) }
                .forEach { resident ->
                    suggestion.addEntry(SuggestionEntry(resident.name))
                }
        }
        return word.map { input ->
            Nodes.getResidentFromName(input)
                ?: throw ArgumentSyntaxException("Resident not found", input, 1)
        }
    }
}
