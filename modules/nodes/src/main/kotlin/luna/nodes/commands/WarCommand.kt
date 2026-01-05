/**
 * Commands for declaring war on other towns, nations
 */

package luna.nodes.commands

import org.bukkit.ChatColor
import luna.nodes.Message
import luna.nodes.Nodes
import luna.nodes.objects.Nation
import luna.nodes.objects.Town
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

/**
 * @command /war
 * Declare war on a town or nation
 *
 * @subcommand /war [town]
 * Declares war on a town
 *
 * @subcommand /war [nation]
 * Declares war on a nation
 */
public class WarCommand : Command("war") {
    init {
        setDefaultExecutor { sender, context ->
            Nodes.war.printInfo(sender, false)
            Message.print(sender, "${ChatColor.BOLD}[Nodes] War commands:")
            Message.print(sender, "/war [town]${ChatColor.WHITE}: Declare war on a town")
            Message.print(sender, "/war [nation]${ChatColor.WHITE}: Declare war on a nation")
        }

        var targetArg = ArgumentType.String("target")

        addSyntax ({ sender, context ->
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
                Message.error(player, "You do not belong to a town")
                return@addSyntax
            }

            val nation = town.nation
            if (nation !== null && town !== nation.capital) {
                Message.error(player, "Only the nation's capital town can declare war")
                return@addSyntax
            }

            if (resident !== town.leader && !town.officers.contains(resident)) {
                Message.error(player, "Only the leader and officers can declare war")
                return@addSyntax
            }

            // parse target
            val target = context[targetArg]

            // 1. try war on nation
            var enemyNation = Nodes.nations.get(target)
            if (enemyNation !== null) {
                declareWar(player, town, enemyNation.capital, nation, enemyNation)
                return@addSyntax
            }

            // 2. try war on town
            //    if town has nation, auto declare war on nation
            val enemyTown = Nodes.towns.get(target)
            if (enemyTown !== null) {
                if (enemyTown === town) {
                    Message.error(player, "You cannot declare war on yourself")
                    return@addSyntax
                }

                enemyNation = enemyTown.nation
                if (enemyNation !== null) {
                    declareWar(player, town, enemyNation.capital, nation, enemyNation)
                } else {
                    declareWar(player, town, enemyTown, nation, enemyNation)
                }
                return@addSyntax
            }

            Message.error(player, "Town or nation \"${target}\" does not exist")

        }, targetArg)
    }
}

// tries to declare war on another town
private fun declareWar(player: Player, town: Town, enemy: Town, townNation: Nation?, enemyNation: Nation?) {
    // cannot declare war on self
    if (town === enemy) {
        Message.error(player, "You cannot declare war on yourself")
        return
    }

    // cannot war allies
    if (town.allies.contains(enemy) || enemy.allies.contains(town)) {
        Message.error(player, "You cannot declare war on an ally")
        return
    }

    // check if already in war
    if (town.enemies.contains(enemy) || enemy.enemies.contains(town)) {
        Message.error(player, "You are already at war with ${enemy.name}")
        return
    }

    // make enemies
    val result = Nodes.addEnemy(town, enemy)
    if (result.isSuccess) {
        if (townNation !== null) {
            if (enemyNation !== null) {
                if (townNation === enemyNation) {
                    Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}${town.name} has declared war on ${enemy.name}!")
                } else {
                    Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}${townNation.name} has declared war on ${enemyNation.name}!")
                }
            } else {
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}${townNation.name} has declared war on ${enemy.name}!")
            }
        } else {
            if (enemyNation !== null) {
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}${town.name} has declared war on ${enemyNation.name}!")
            } else {
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}${town.name} has declared war on ${enemy.name}!")
            }
        }
    }
}
