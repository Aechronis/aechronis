/**
 * Commands for breaking alliance with other towns, nations
 */

package luna.nodes.commands

import org.bukkit.ChatColor
import luna.nodes.Message
import luna.nodes.Nodes
import luna.nodes.constants.ErrorNotAllies
import luna.nodes.objects.Nation
import luna.nodes.objects.Town
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class UnallyCommand : Command("unally") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "[Nodes] Ally commands:")
            Message.print(sender, "/ally [town]${ChatColor.WHITE}: Offer/accept alliance with town")
            Message.print(sender, "/ally [nation]${ChatColor.WHITE}: Offer/accept alliance with nation")
            Message.print(sender, "/unally [town]${ChatColor.WHITE}: Break alliance with town")
            Message.print(sender, "/unally [nation]${ChatColor.WHITE}: Break alliance with nation")
        }

        val targetArg = ArgumentType.String("target")

        addSyntax( { sender, context ->
            if (sender !is Player) {
                return@addSyntax
            }

            val player: Player = sender
            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                return@addSyntax
            }

            val nation = town.nation
            if (nation !== null && town !== nation.capital) {
                Message.error(player, "Only the nation's capital town can offer/accept alliances")
                return@addSyntax
            }

            if (resident !== town.leader && !town.officers.contains(resident)) {
                Message.error(player, "Only the leader and officers can offer/accept alliances")
                return@addSyntax
            }

            val target = context[targetArg]

            // 1. try nation
            var otherNation = Nodes.nations.get(target)
            if (otherNation !== null) {
                breakAlliance(player, town, otherNation.capital, nation, otherNation)
                return@addSyntax
            }

            // 2. try town
            //    if town has nation, use nation
            val otherTown = Nodes.towns.get(target)
            if (otherTown !== null) {
                otherNation = otherTown.nation
                if (otherNation !== null) {
                    breakAlliance(player, town, otherNation.capital, nation, otherNation)
                } else {
                    breakAlliance(player, town, otherTown, nation, otherNation)
                }
                return@addSyntax
            }

            Message.error(player, "Town or nation \"${target}\" does not exist")

            return@addSyntax
        }, targetArg)
    }
}

// break alliance with other side
private fun breakAlliance(player: Player, town: Town, other: Town, townNation: Nation?, otherNation: Nation?) {
    if (town === other) {
        Message.error(player, "You cannot ally yourself.")
        return
    }

    val result = Nodes.removeAlly(town, other)
    if (result.isSuccess) {
        // broadcast message
        val thisSide = if (townNation !== null) {
            townNation.name
        } else {
            town.name
        }

        val otherSide = if (otherNation !== null) {
            otherNation.name
        } else {
            other.name
        }

        Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}$thisSide has ended its alliance with $otherSide")
    } else {
        when (result.exceptionOrNull()) {
            ErrorNotAllies -> Message.error(player, "You are not allied with this town or nation")
        }
    }
}
