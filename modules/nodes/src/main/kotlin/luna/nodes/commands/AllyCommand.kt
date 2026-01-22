/**
 * Commands for offering/accepting alliances
 */

package luna.nodes.commands

import luna.nodes.Message
import luna.nodes.commands.arguments.ArgumentNation
import luna.nodes.constants.ErrorAllyRequestAlreadyAllies
import luna.nodes.constants.ErrorAllyRequestAlreadyCreated
import luna.nodes.constants.ErrorAllyRequestEnemies
import luna.nodes.war.Alliance
import luna.nodes.war.AllianceRequest
import luna.nodes.objects.Command


class AllyCommand : Command("ally") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /ally <nation-name>")
        }

        val nationArg = ArgumentNation.create("nation-name")

        addSyntax( { player, resident, town, nation, context ->
            if (town !== nation.capital) {
                Message.error(player, "Only the nation's capital town can offer/accept alliances")
                return@addSyntax
            }

            if (resident !== town.leader && !town.officers.contains(resident)) {
                Message.error(player, "Only the leader and officers can offer/accept alliances")
                return@addSyntax
            }

            if (nation === context[nationArg]) {
                Message.error(player, "You cannot ally yourself.")
                return@addSyntax
            }

            val result = Alliance.request(nation, context[nationArg])
            if (result.isSuccess) {
                when (result.getOrNull()) {
                    // message that alliance is being requested
                    AllianceRequest.NEW -> {
                        val thisSideMsg = "You are offering an alliance to ${context[nationArg].name}"
                        for (town in nation.towns) {
                            for (r in town.residents) {
                                val p = r.player()
                                if (p !== null) {
                                    Message.print(p, thisSideMsg)
                                }
                            }
                        }

                        val otherSideMsg = "${nation.name} is offering an alliance, use \"/ally ${nation.name}\" to accept"
                        for (town in context[nationArg].towns) {
                            for (r in town.residents) {
                                val p = r.player()
                                if (p !== null) {
                                    Message.print(p, otherSideMsg)
                                }
                            }
                        }
                    }

                    // broadcast that alliance was created
                    AllianceRequest.ACCEPTED -> {
                        Message.broadcast("${nation.name} has formed an alliance with ${context[nationArg].name}")
                    }

                    null -> {}
                }
            } else {
                when (result.exceptionOrNull()) {
                    ErrorAllyRequestEnemies -> Message.error(player, "You cannot ally an enemy nation")
                    ErrorAllyRequestAlreadyAllies -> Message.error(player, "You are already allied with this nation")
                    ErrorAllyRequestAlreadyCreated -> Message.error(player, "You already sent an alliance request")
                }
            }
        }, nationArg)
    }
}