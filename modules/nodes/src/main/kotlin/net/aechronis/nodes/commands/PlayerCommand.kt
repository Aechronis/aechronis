/**
 * Commands for viewing resident info
 */

package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.commands.arguments.ArgumentResident
import net.aechronis.nodes.objects.NodesCommand

class PlayerCommand : NodesCommand("player", null, "p") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage:")
            Message.print(player, "/player")
            Message.print(player, "/player <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, context ->
            resident.printInfo(player)
        })

        addSyntax({ player, resident, context ->
            context[playerArg].printInfo(player)
        }, playerArg)
    }
}
