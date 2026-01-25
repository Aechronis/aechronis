package luna.nodes.commands.arguments

import luna.nodes.Nodes
import luna.nodes.objects.Resident
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentResidentArray {
    /**
     * Creates an argument that accepts multiple residents and returns a list of Resident objects.
     */
    fun create(id: String): Argument<List<Resident>> {
        val stringArray = ArgumentType.StringArray(id)
        stringArray.setSuggestionCallback { sender, context, suggestion ->
            val input = suggestion.input.substringAfterLast(" ").lowercase()

            Nodes.residents.values
                .filter { it.name.startsWith(input) }
                .forEach { resident ->
                    suggestion.addEntry(SuggestionEntry(resident.name))
                }
        }
        return stringArray.map { inputs ->
            inputs.map { input ->
                Nodes.getResidentFromName(input)
                    ?: throw ArgumentSyntaxException("Resident not found", input, 1)
            }
        }
    }
}
