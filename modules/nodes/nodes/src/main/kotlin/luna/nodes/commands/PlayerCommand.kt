///**
// * Commands for viewing resident info
// */
//
//package luna.nodes.commands
//
//import org.bukkit.command.Command
//import org.bukkit.command.CommandExecutor
//import org.bukkit.command.CommandSender
//import org.bukkit.command.TabCompleter
//import org.bukkit.entity.Player
//import luna.nodes.Message
//import luna.nodes.Nodes
//import luna.nodes.utils.string.filterResident
//
///**
// * @command /player
// * Print info about a player
// */
//public class PlayerCommand :
//    CommandExecutor,
//    TabCompleter {
//
//    override fun onCommand(sender: CommandSender, cmd: Command, commandLabel: String, args: Array<String>): Boolean {
//        // no args, use sender
//        if (args.size == 0) {
//            if (sender is Player) {
//                val resident = Nodes.getResident(sender)
//                if (resident != null) {
//                    resident.printInfo(sender)
//                }
//            } else {
//                Message.error(sender, "Usage: \"/player name\" to print player info")
//            }
//        }
//        // parse player name
//        else if (args.size >= 1) {
//            val resident = Nodes.getResidentFromName(args[0])
//            if (resident != null) {
//                resident.printInfo(sender)
//            } else {
//                Message.error(sender, "Invalid player name \"${args[0]}\"")
//            }
//        }
//
//        return true
//    }
//
//    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
//        if (args.size == 1) {
//            return filterResident(args[0])
//        }
//
//        return listOf()
//    }
//}
