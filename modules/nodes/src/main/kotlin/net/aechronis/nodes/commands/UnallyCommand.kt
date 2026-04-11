/**
 * Commands for breaking alliance with other nations
 * Alliances are between nations only.
 */

package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.commands.arguments.ArgumentNation
import net.aechronis.nodes.constants.ErrorNotAllies
import net.aechronis.nodes.objects.Command
import net.aechronis.nodes.utils.ChatColor

class UnallyCommand : Command("unally") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /unally <nation-name>")
        }

        val nationArg = ArgumentNation.create("nation-name")

        addSyntax({ player, resident, town, nation, context ->
            if (town !== nation.capital) {
                Message.error(player, "Only the nation's capital town can break alliances")
                return@addSyntax
            }

            if (resident !== town.leader && !town.officers.contains(resident)) {
                Message.error(player, "Only the leader and officers can break alliances")
                return@addSyntax
            }

            if (nation === context[nationArg]) {
                Message.error(player, "You cannot unally yourself.")
                return@addSyntax
            }

            val result = Nodes.removeAlly(nation, context[nationArg])
            if (result.isSuccess) {
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}${nation.name} has ended its alliance with ${context[nationArg].name}")
            } else {
                when (result.exceptionOrNull()) {
                    ErrorNotAllies -> Message.error(player, "You are not allied with this nation")
                }
            }
        }, nationArg)
    }
}
