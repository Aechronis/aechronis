/**
 * Commands for viewing resident info
 */

package luna.nodes.nodes.commands

import luna.nodes.nodes.Message
import luna.nodes.nodes.commands.arguments.ArgumentResident
import luna.nodes.nodes.objects.Command

class PlayerCommand : Command("player", null,"p") {
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
