package luna.nodes.combat.commands.arguments

import luna.nodes.combat.objects.Item
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentItem {
    /**
     * Creates an argument that autocompletes and returns an Item object.
     */
    fun create(id: String): Argument<Item> {
        val word = ArgumentType.Word(id)
        word.setSuggestionCallback { sender, context, suggestion ->
            val input = suggestion.input.substringAfterLast(" ").lowercase()

            Item.registeredItems.values
                .filter { it.name.lowercase().startsWith(input) }
                .forEach { item ->
                    suggestion.addEntry(SuggestionEntry(item.name))
                }
        }
        return word.map { input ->
            Item.getFromName(input)
                ?: throw ArgumentSyntaxException("Item not found", input, 1)
        }
    }
}
