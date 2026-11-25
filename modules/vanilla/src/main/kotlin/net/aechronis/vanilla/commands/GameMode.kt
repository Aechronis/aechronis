package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.tag.Tag

/** Set while an admin put the player in adventure with /gamemode, so land checks leave the mode alone. */
val ADMIN_ADVENTURE: Tag<Boolean> = Tag.Boolean("admin-adventure")

private fun Player.setAdminGameMode(mode: GameMode) {
    setTag(ADMIN_ADVENTURE, mode == GameMode.ADVENTURE)
    gameMode = mode
}

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
            targets.forEach { it.setAdminGameMode(gameMode) }
        }, gameModeArg, playerArg)

        addSyntax({ sender: Player, context ->
            sender.setAdminGameMode(context[gameModeArg])
        }, gameModeArg)
    }
}
