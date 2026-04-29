package net.aechronis.combat.commands.arguments

import net.aechronis.combat.objects.Hat
import net.aechronis.combat.objects.Item
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry

object ArgumentHat {
    /**
     * Creates an argument that autocompletes and returns a Hat object.
     */
    fun create(id: String): Argument<Hat> {
        val word = ArgumentType.Word(id)
        word.setSuggestionCallback { _, _, suggestion ->
            val input = suggestion.input.substringAfterLast(" ").lowercase()

            Item.registeredItems.values
                .filterIsInstance<Hat>()
                .filter { it.name.lowercase().startsWith(input) }
                .forEach { hat ->
                    suggestion.addEntry(SuggestionEntry(hat.name))
                }
        }
        return word.map { input ->
            val item = Item.getFromName(input)
            item as? Hat ?: throw ArgumentSyntaxException("Hat not found", input, 1)
        }
    }
}
