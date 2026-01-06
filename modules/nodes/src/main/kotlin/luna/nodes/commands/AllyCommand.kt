/**
 * Commands for offering/accepting alliances
 */

package luna.nodes.commands

import luna.nodes.Message
import luna.nodes.Nodes
import luna.nodes.objects.Nation
import luna.nodes.objects.Town
import luna.nodes.war.Alliance
import luna.nodes.war.AllianceRequest
import luna.nodes.war.ErrorAllyRequestAlreadyAllies
import luna.nodes.war.ErrorAllyRequestAlreadyCreated
import luna.nodes.war.ErrorAllyRequestEnemies
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import org.bukkit.ChatColor


class AllyCommand : Command("ally") {
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
                offerAlliance(player, town, otherNation.capital, nation, otherNation)
                return@addSyntax
            }

            // 2. try town
            //    if town has nation, use nation
            val otherTown = Nodes.towns.get(target)
            if (otherTown !== null) {
                otherNation = otherTown.nation
                if (otherNation !== null) {
                    offerAlliance(player, town, otherNation.capital, nation, otherNation)
                } else {
                    offerAlliance(player, town, otherTown, nation, otherNation)
                }
                return@addSyntax
            }

            Message.error(player, "Town or nation \"${target}\" does not exist")
        }, targetArg)
    }
}

// offer alliance, other side must offer alliance to accept
private fun offerAlliance(player: Player, town: Town, other: Town, townNation: Nation?, otherNation: Nation?) {
    if (town === other) {
        Message.error(player, "You cannot ally yourself.")
        return
    }

    val result = Alliance.request(town, other)
    if (result.isSuccess) {
        val thisSideName = if (townNation !== null) {
            townNation.name
        } else {
            town.name
        }

        val otherSideName = if (otherNation !== null) {
            otherNation.name
        } else {
            other.name
        }

        when (result.getOrNull()) {
            // message that alliance is being requested
            AllianceRequest.NEW -> {
                val thisSideMsg = "You are offering an alliance to $otherSideName"
                for (r in town.residents) {
                    val player = r.player()
                    if (player !== null) {
                        Message.print(player, thisSideMsg)
                    }
                }

                val otherSideMsg = "$thisSideName is offering an alliance, use \"/ally ${thisSideName}\" to accept"
                for (r in other.residents) {
                    val player = r.player()
                    if (player !== null) {
                        Message.print(player, otherSideMsg)
                    }
                }
            }

            // broadcast that alliance was created
            AllianceRequest.ACCEPTED -> {
                Message.broadcast("$thisSideName has formed an alliance with $otherSideName")
            }

            null -> {}
        }
    } else {
        when (result.exceptionOrNull()) {
            ErrorAllyRequestEnemies -> Message.error(player, "You cannot ally an enemy")
            ErrorAllyRequestAlreadyAllies -> Message.error(player, "You are already allied with this town or nation")
            ErrorAllyRequestAlreadyCreated -> Message.error(player, "You already sent an alliance request")
        }
    }
}