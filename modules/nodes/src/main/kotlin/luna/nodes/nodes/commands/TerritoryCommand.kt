/**
 * Commands for viewing territory, alias of /nodes territory
 */

package luna.nodes.nodes.commands

import luna.nodes.nodes.Message
import luna.nodes.nodes.Nodes
import luna.nodes.nodes.commands.arguments.ArgumentTerritory
import luna.nodes.nodes.objects.Command

class TerritoryCommand : Command("territory") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage:")
            Message.print(player, "/territory")
            Message.print(player, "/territory <territory-id>")
        }

        val territoryArg = ArgumentTerritory.create("territory-id")

        addSyntax({ player, resident, context ->
            val territory = Nodes.getTerritoryFromPlayer(player)

            if (territory == null) {
                Message.error(player, "No territory at current location")
                return@addSyntax
            }

            territory.printInfo(player)
        })

        addSyntax({ player, resident, context ->
            context[territoryArg].printInfo(player)
        }, territoryArg)
    }
}
