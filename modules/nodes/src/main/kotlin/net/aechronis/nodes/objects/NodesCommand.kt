/**
 * Utility class to reduce boilerplate in command handlers
 */

package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.utils.Command
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.entity.Player

open class NodesCommand(
    name: String,
    permission: String? = null,
    vararg aliases: String,
) : Command(name, permission, *aliases) {

    /**
     * Add a default executor that requires the sender to be a resident.
     */
    fun setDefaultExecutor(
        executor: (player: Player, resident: Resident, context: CommandContext) -> Unit,
    ) {
        super.setDefaultExecutor { player: Player, context ->
            val resident = Nodes.getResident(player)
            executor(player, resident!!, context)
        }
    }

    /**
     * Add a syntax that requires the sender to be a resident.
     */
    fun addSyntax(
        executor: (player: Player, resident: Resident, context: CommandContext) -> Unit,
        vararg args: Argument<*>,
    ) {
        super.addSyntax({ player: Player, context ->
            val resident = Nodes.getResident(player)
            executor(player, resident!!, context)
        }, *args)
    }

    /**
     * Add a syntax that requires the sender to be a resident that is in a town
     */
    fun addSyntax(
        executor: (player: Player, resident: Resident, town: Town, context: CommandContext) -> Unit,
        vararg args: Argument<*>,
    ) {
        super.addSyntax({ player: Player, context ->
            val resident = Nodes.getResident(player)
            if (resident == null) {
                Message.error(player, "This command can only be used by players")
                return@addSyntax
            }

            val town = Nodes.getTownFromPlayer(player)
            if (town == null) {
                Message.error(player, "You must be in a town to use this command")
                return@addSyntax
            }

            executor(player, resident, town, context)
        }, *args)
    }

    /**
     * Add a syntax that requires the sender to be a resident that is in a nation
     */
    fun addSyntax(
        executor: (player: Player, resident: Resident, town: Town, nation: Nation, context: CommandContext) -> Unit,
        vararg args: Argument<*>,
    ) {
        super.addSyntax({ player: Player, context ->
            val resident = Nodes.getResident(player)
            if (resident == null) {
                Message.error(player, "This command can only be used by players")
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You must be in a town to use this command")
                return@addSyntax
            }

            val nation = resident.nation
            if (nation == null) {
                Message.error(player, "You must be in a nation to use this command")
                return@addSyntax
            }

            executor(player, resident, town, nation, context)
        }, *args)
    }
}
