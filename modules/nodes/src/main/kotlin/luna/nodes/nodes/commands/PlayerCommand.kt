/**
 * Commands for viewing resident info
 */

package luna.nodes.nodes.commands

import luna.nodes.nodes.Message
import luna.nodes.nodes.commands.arguments.ArgumentResident
import luna.nodes.nodes.objects.Command

class PlayerCommand : Command("player", "p") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage:")
            Message.print(sender, "/player")
            Message.print(sender, "/player <player-name>")
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
