package net.aechronis.vanilla.commands

import net.aechronis.server.hasPermission
import net.aechronis.server.modules.ModulePermissions
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player

private const val CREATIVE_PERMISSION = "vanilla.gamemode.creative"

/** Creative needs its own node on top of vanilla.gamemode, so it can be limited to admins and above. */
internal fun mayUse(
    player: Player,
    gameMode: GameMode,
): Boolean {
    if (gameMode != GameMode.CREATIVE || player.hasPermission(CREATIVE_PERMISSION)) return true
    player.sendMessage(Component.text("Only admins can use creative mode", NamedTextColor.RED))
    return false
}

class GameMode : Command("gamemode", "vanilla.gamemode") {
    init {
        ModulePermissions.register(CREATIVE_PERMISSION)
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/gamemode <gamemode>", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/gamemode <gamemode> <player>", NamedTextColor.LIGHT_PURPLE))
        }

        val playerArg = PlayerTargets.argument("player-name")
        val gameModeArg = ArgumentType.Enum("gamemode", GameMode::class.java)

        addSyntax({ sender: Player, context ->
            val gameMode = context[gameModeArg]
            if (!mayUse(sender, gameMode)) return@addSyntax
            val targets = PlayerTargets.resolve(sender, context[playerArg]) ?: return@addSyntax
            targets.forEach { it.gameMode = gameMode }
        }, gameModeArg, playerArg)

        addSyntax({ sender: Player, context ->
            val gameMode = context[gameModeArg]
            if (mayUse(sender, gameMode)) sender.gameMode = gameMode
        }, gameModeArg)
    }
}
