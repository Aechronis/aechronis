package net.aechronis.vanilla.commands

import net.aechronis.vanilla.managers.Commands.open
import net.aechronis.vanilla.utils.Message
import net.aechronis.vanilla.utils.VanillaCommand
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class InventorySee : VanillaCommand("invsee", "vanilla.invsee", "inventorysee") {
    private val playerArg = ArgumentType.Entity("player").singleEntity(true).onlyPlayers(true)

    init {
        setDefaultExecutor { player: Player, _ ->
            Message.print(player, "Usage:")
            Message.print(player, "/invsee <player>")
        }

        addSyntax({ player: Player, context ->
            val target =
                context[playerArg].findFirstPlayer(player) ?: run {
                    Message.error(player, "Player not found.")
                    return@addSyntax
                }
            open(player, target)
        }, playerArg)
    }
}
