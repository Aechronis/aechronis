/**
 * Commands for viewing territory, alias of /nodes territory
 */

package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.commands.arguments.ArgumentTerritory
import net.aechronis.nodes.objects.Command

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
