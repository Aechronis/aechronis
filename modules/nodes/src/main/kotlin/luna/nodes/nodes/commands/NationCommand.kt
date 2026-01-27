/**
 * /nation (/n) command
 */

package luna.nodes.nodes.commands

import luna.nodes.nodes.Message
import luna.nodes.nodes.Nodes
import luna.nodes.nodes.commands.arguments.ArgumentNation
import luna.nodes.nodes.objects.Command
import luna.nodes.nodes.utils.ChatColor

class NationCommand : Command("n", "nation") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "${ChatColor.BOLD}[Nodes] Nation commands:")
            Message.print(sender, "/nation list${ChatColor.WHITE}: List all nations")
            Message.print(sender, "/nation online${ChatColor.WHITE}: View nation's online players")
            Message.print(sender, "/nation info${ChatColor.WHITE}: View nation details")
        }

        // no args, print current nation info
        addSyntax({ player, resident, context ->
            // print player's nation info
            if (resident.nation != null) {
                resident.nation!!.printInfo(player)
                Message.print(player, "Use \"/nation help\" to view commands")
            } else {
                Message.print(player, "${ChatColor.BOLD}[Nodes] Nation commands:")
                Message.print(player, "/nation list${ChatColor.WHITE}: List all nations")
                Message.print(player, "/nation online${ChatColor.WHITE}: View nation's online players")
                Message.print(player, "/nation info${ChatColor.WHITE}: View nation details")
            }
        })

        addSubcommand(NationHelpCommand())
        addSubcommand(NationListCommand())
        addSubcommand(NationOnlineCommand())
        addSubcommand(NationInfoCommand())
    }
}

class NationHelpCommand : Command("help") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "${ChatColor.BOLD}[Nodes] Nation commands:")
            Message.print(sender, "/nation list${ChatColor.WHITE}: List all nations")
            Message.print(sender, "/nation online${ChatColor.WHITE}: View nation's online players")
            Message.print(sender, "/nation color${ChatColor.WHITE}: Set nation color on map")
        }
    }
}

class NationListCommand : Command("list") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nation list")
        }

        addSyntax({ player, resident, context ->
            Message.print(player, "${ChatColor.BOLD}Nation - Population - Towns")
            val nationsList = ArrayList(Nodes.nations.values)
            nationsList.sortByDescending { it.residents.size }
            for (n in nationsList) {
                val townsList = ArrayList(n.towns)
                townsList.sortByDescending { it.residents.size }
                var towns = ""
                for ((i, t) in townsList.withIndex()) {
                    towns += t.name
                    towns += " (${t.residents.size})"
                    if (i < n.towns.size - 1) {
                        towns += ", "
                    }
                }
                Message.print(player, "${n.name} ${ChatColor.WHITE}- ${n.residents.size} - $towns")
            }
        })
    }
}

class NationOnlineCommand : Command("online") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage:")
            Message.print(sender, "/nation online")
            Message.print(sender, "/nation online <nation-name>")
        }

        val nationArg = ArgumentNation.create("nation-name")

        addSyntax({ player, resident, town, nation, context ->
            val numPlayersOnline = nation.playersOnline.size
            val playersOnline = nation.playersOnline.joinToString(", ", transform = { p -> p.username })
            Message.print(player, "Players online in nation ${nation.name} [$numPlayersOnline]: ${ChatColor.WHITE}$playersOnline")
        })

        addSyntax({ player, resident, context ->
            val numPlayersOnline = context[nationArg].playersOnline.size
            val playersOnline = context[nationArg].playersOnline.joinToString(", ", transform = { p -> p.username })
            Message.print(player, "Players online in nation ${context[nationArg].name} [$numPlayersOnline]: ${ChatColor.WHITE}$playersOnline")
        }, nationArg)
    }
}

class NationInfoCommand : Command("info") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage:")
            Message.print(sender, "/nation info")
            Message.print(sender, "/nation info <nation-name>")
        }

        val nationArg = ArgumentNation.create("nation-name")

        addSyntax({ player, resident, town, nation, context ->
            nation.printInfo(player)
        })

        addSyntax({ player, resident, context ->
            context[nationArg].printInfo(player)
        }, nationArg)
    }
}
