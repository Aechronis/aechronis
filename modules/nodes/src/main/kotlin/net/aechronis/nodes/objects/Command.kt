/**
 * Utility class to reduce boilerplate in command handlers
 */

package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.utils.hasPermission
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.entity.Player

open class Command(
    name: String,
    val permission: String? = null,
    vararg aliases: String,
) : Command(name, *aliases) {

    /**
     * Add a default executor that requires the sender to be a player.
     */
    fun setDefaultExecutor(
        executor: (player: Player, resident: Resident, context: CommandContext) -> Unit,
    ) {
        setDefaultExecutor { sender, context ->
            if (sender !is Player) {
                Message.error(sender, "This command can only be used by players")
                return@setDefaultExecutor
            }

            if (permission != null) {
                if (!hasPermission(sender, permission)) {
                    Message.error(sender, "You don't have permission to use this command")
                    return@setDefaultExecutor
                }
            }

            val resident = Nodes.getResident(sender)
            executor(sender, resident!!, context)
        }
    }

    /**
     * Add a syntax that requires the sender to be a player.
     */
    fun addSyntax(
        executor: (player: Player, resident: Resident, context: CommandContext) -> Unit,
        vararg args: Argument<*>,
    ) {
        addSyntax({ sender, context ->
            if (sender !is Player) {
                Message.error(sender, "This command can only be used by players")
                return@addSyntax
            }

            if (permission != null) {
                if (!hasPermission(sender, permission)) {
                    Message.error(sender, "You don't have permission to use this command")
                    return@addSyntax
                }
            }

            val resident = Nodes.getResident(sender)
            executor(sender, resident!!, context)
        }, *args)
    }

    /**
     * Add a syntax that requires the sender to be a player that is in a town
     */
    fun addSyntax(
        executor: (player: Player, resident: Resident, town: Town, context: CommandContext) -> Unit,
        vararg args: Argument<*>,
    ) {
        addSyntax({ sender, context ->
            if (sender !is Player) {
                Message.error(sender, "This command can only be used by players")
                return@addSyntax
            }

            if (permission != null) {
                if (!hasPermission(sender, permission)) {
                    Message.error(sender, "You don't have permission to use this command")
                    return@addSyntax
                }
            }

            val resident = Nodes.getResident(sender)
            if (resident == null) {
                Message.error(sender, "This command can only be used by players")
                return@addSyntax
            }

            val town = Nodes.getTownFromPlayer(sender)
            if (town == null) {
                Message.error(sender, "You must be in a town to use this command")
                return@addSyntax
            }

            executor(sender, resident, town, context)
        }, *args)
    }

    /**
     * Add a syntax that requires the sender to be a player that is in a nation
     */
    fun addSyntax(
        executor: (player: Player, resident: Resident, town: Town, nation: Nation, context: CommandContext) -> Unit,
        vararg args: Argument<*>,
    ) {
        addSyntax({ sender, context ->
            if (sender !is Player) {
                Message.error(sender, "This command can only be used by players")
                return@addSyntax
            }

            if (permission != null) {
                if (!hasPermission(sender, permission)) {
                    Message.error(sender, "You don't have permission to use this command")
                    return@addSyntax
                }
            }

            val resident = Nodes.getResident(sender)
            if (resident == null) {
                Message.error(sender, "This command can only be used by players")
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(sender, "You must be in a town to use this command")
                return@addSyntax
            }

            val nation = resident.nation
            if (nation == null) {
                Message.error(sender, "You must be in a nation to use this command")
                return@addSyntax
            }

            executor(sender, resident, town, nation, context)
        }, *args)
    }
}
