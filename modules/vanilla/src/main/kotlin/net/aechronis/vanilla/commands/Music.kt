package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Music
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Music : Command("music", "vanilla.music") {
    init {
        val discsByTitle = Vanilla.config.musicDiscs.associateBy { it.songName }
        val titleArg = ArgumentType.Word("title").from(*discsByTitle.keys.toTypedArray())

        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage: /music <title>", NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ player: Player, context ->
            val disc = discsByTitle[context[titleArg]] ?: return@addSyntax
            if (!player.inventory.addItemStack(Music.itemFor(disc))) {
                player.sendMessage(Component.text("Your inventory is full", NamedTextColor.RED))
            }
        }, titleArg)
    }
}
