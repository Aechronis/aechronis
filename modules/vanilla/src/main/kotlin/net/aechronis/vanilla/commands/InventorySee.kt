package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Commands.open
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class InventorySee : Command("invsee", "vanilla.invsee", "inventorysee") {
    private val playerArg = ArgumentType.Entity("player").singleEntity(true).onlyPlayers(true)

    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/invsee <player>", NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ player: Player, context ->
            val target =
                context[playerArg].findFirstPlayer(player) ?: run {
                    player.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                    return@addSyntax
                }
            open(player, target)
        }, playerArg)
    }
}
