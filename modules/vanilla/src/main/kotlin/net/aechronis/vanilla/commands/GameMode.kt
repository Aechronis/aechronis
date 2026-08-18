package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player

class GameMode : Command("gamemode", "vanilla.gamemode") {
    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/gamemode <gamemode>", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/gamemode <gamemode> <player>", NamedTextColor.LIGHT_PURPLE))
        }

        val playerArg = PlayerTargets.argument("player-name")
        val gameModeArg = ArgumentType.Enum("gamemode", GameMode::class.java)

        addSyntax({ sender: Player, context ->
            val targets = PlayerTargets.resolve(sender, context[playerArg]) ?: return@addSyntax
            val gameMode = context[gameModeArg]
            targets.forEach { it.gameMode = gameMode }
        }, gameModeArg, playerArg)

        addSyntax({ sender: Player, context ->
            sender.gameMode = context[gameModeArg]
        }, gameModeArg)
    }
}
