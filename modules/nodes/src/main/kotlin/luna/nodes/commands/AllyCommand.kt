///**
// * Commands for offering/accepting alliances
// */
//
//package luna.nodes.commands
//
//import org.bukkit.ChatColor
//import org.bukkit.command.Command
//import org.bukkit.command.CommandExecutor
//import org.bukkit.command.CommandSender
//import org.bukkit.command.TabCompleter
//import org.bukkit.entity.Player
//import luna.nodes.Message
//import luna.nodes.Nodes
//import luna.nodes.objects.Nation
//import luna.nodes.objects.Town
//import luna.nodes.utils.string.filterTownOrNation
//import luna.nodes.war.Alliance
//import luna.nodes.war.AllianceRequest
//import luna.nodes.war.ErrorAllyRequestAlreadyAllies
//import luna.nodes.war.ErrorAllyRequestEnemies
//import luna.nodes.war.ErrorAllyRequestAlreadyCreated
//
///**
// * @command /ally
// * Offer or break alliances with towns or nations
// *
// * @subcommand /ally [town]
// * Offer alliance to a town
// *
// * @subcommand /ally [nation]
// * Offer alliance to a nation
// */
//public class AllyCommand :
//    CommandExecutor,
//    TabCompleter {
//
//    override fun onCommand(sender: CommandSender, cmd: Command, commandLabel: String, args: Array<String>): Boolean {
//        // no args, print help
//        if (args.size == 0) {
//            printHelp(sender)
//            return true
//        }
//
//        if (!(sender is Player)) {
//            return true
//        }
//
//        val player: Player = sender
//        val resident = Nodes.getResident(player)
//        if (resident == null) {
//            return true
//        }
//
//        val town = resident.town
//        if (town == null) {
//            return true
//        }
//
//        val nation = town.nation
//        if (nation !== null && town !== nation.capital) {
//            Message.error(player, "Only the nation's capital town can offer/accept alliances")
//            return true
//        }
//
//        if (resident !== town.leader && !town.officers.contains(resident)) {
//            Message.error(player, "Only the leader and officers can offer/accept alliances")
//            return true
//        }
//
//        parseTownOrNationName(player, args, town, nation)
//
//        return true
//    }
//
//    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
//        if (args.size > 0) {
//            return filterTownOrNation(args[0])
//        }
//
//        return listOf()
//    }
//
//    private fun printHelp(sender: CommandSender) {
//        Message.print(sender, "[Nodes] Ally commands:")
//        Message.print(sender, "/ally [town]${ChatColor.WHITE}: Offer/accept alliance with town")
//        Message.print(sender, "/ally [nation]${ChatColor.WHITE}: Offer/accept alliance with nation")
//        Message.print(sender, "/unally [town]${ChatColor.WHITE}: Break alliance with town")
//        Message.print(sender, "/unally [nation]${ChatColor.WHITE}: Break alliance with nation")
//        return
//    }
//
//    private fun parseTownOrNationName(player: Player, args: Array<String>, town: Town, townNation: Nation?) {
//        val target = args[0]
//
//        // 1. try nation
//        var otherNation = Nodes.nations.get(target)
//        if (otherNation !== null) {
//            offerAlliance(player, town, otherNation.capital, townNation, otherNation)
//            return
//        }
//
//        // 2. try town
//        //    if town has nation, use nation
//        val otherTown = Nodes.towns.get(target)
//        if (otherTown !== null) {
//            otherNation = otherTown.nation
//            if (otherNation !== null) {
//                offerAlliance(player, town, otherNation.capital, townNation, otherNation)
//            } else {
//                offerAlliance(player, town, otherTown, townNation, otherNation)
//            }
//            return
//        }
//
//        Message.error(player, "Town or nation \"${target}\" does not exist")
//    }
//
//    // offer alliance, other side must offer alliance to accept
//    private fun offerAlliance(player: Player, town: Town, other: Town, townNation: Nation?, otherNation: Nation?) {
//        if (town === other) {
//            Message.error(player, "You cannot ally yourself.")
//            return
//        }
//
//        val result = Alliance.request(town, other)
//        if (result.isSuccess) {
//            val thisSideName = if (townNation !== null) {
//                townNation.name
//            } else {
//                town.name
//            }
//
//            val otherSideName = if (otherNation !== null) {
//                otherNation.name
//            } else {
//                other.name
//            }
//
//            when (result.getOrNull()) {
//                // message that alliance is being requested
//                AllianceRequest.NEW -> {
//                    val thisSideMsg = "You are offering an alliance to $otherSideName"
//                    for (r in town.residents) {
//                        val player = r.player()
//                        if (player !== null) {
//                            Message.print(player, thisSideMsg)
//                        }
//                    }
//
//                    val otherSideMsg = "$thisSideName is offering an alliance, use \"/ally ${thisSideName}\" to accept"
//                    for (r in other.residents) {
//                        val player = r.player()
//                        if (player !== null) {
//                            Message.print(player, otherSideMsg)
//                        }
//                    }
//                }
//
//                // broadcast that alliance was created
//                AllianceRequest.ACCEPTED -> {
//                    Message.broadcast("$thisSideName has formed an alliance with $otherSideName")
//                } null -> {}
//            }
//        } else {
//            when (result.exceptionOrNull()) {
//                ErrorAllyRequestEnemies -> Message.error(player, "You cannot ally an enemy")
//                ErrorAllyRequestAlreadyAllies -> Message.error(player, "You are already allied with this town or nation")
//                ErrorAllyRequestAlreadyCreated -> Message.error(player, "You already sent an alliance request")
//            }
//        }
//
//        // TODO alliance requests
//    }
//}
