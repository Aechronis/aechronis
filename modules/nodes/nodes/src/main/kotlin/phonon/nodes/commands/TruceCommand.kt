/**
 * Commands for viewing town's truces
 */

package phonon.nodes.commands

import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.war.Truce

class TruceCommand : Command("truce") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /truce")
        }

        val townArg = ArgumentType.String("town")

        addSyntax({ sender, context ->
            if (!(sender is Player)) {
                return@addSyntax
            }

            val player: Player = sender
            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You have no town")
                return@addSyntax
            }

            Truce.printTownTruces(player, town)
        })

        addSyntax({ sender, context ->
            val townName = context[townArg]
            val town = Nodes.getTownFromName(townName)
            if (town !== null) {
                Truce.printTownTruces(sender, town)
                Message.print(sender, "Use \"/peace ${townName}\" to negotiate a treaty.")
            } else {
            Message.error(sender, "Town \"${townName}\" does not exist")
            }
        }, townArg)
    }
}
