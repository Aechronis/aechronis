package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Music
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player

class Music : Command("music", "vanilla.music") {
    init {
        // Songs load after commands register, so they're looked up when the command runs
        val titleArg =
            ArgumentType.Word("title").setSuggestionCallback { _, _, suggestion ->
                val typed = PlayerTargets.typedToken(suggestion.input).lowercase()
                Music.discs
                    .map { it.songName }
                    .filter { it.startsWith(typed) }
                    .forEach { suggestion.addEntry(SuggestionEntry(it)) }
            }

        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage: /music <title>", NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ player: Player, context ->
            val disc = Music.disc(context[titleArg])
            if (disc == null) {
                player.sendMessage(Component.text("Unknown song: ${context[titleArg]}", NamedTextColor.RED))
                return@addSyntax
            }
            if (!player.inventory.addItemStack(Music.itemFor(disc))) {
                player.sendMessage(Component.text("Your inventory is full", NamedTextColor.RED))
            }
        }, titleArg)
    }
}
