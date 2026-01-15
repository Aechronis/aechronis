/**
 * General commands for info
 * /nodes [command]
 */

package luna.nodes.commands


import luna.nodes.utils.ChatColor
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
//import org.bukkit.command.CommandExecutor
//import org.bukkit.command.CommandSender
//import org.bukkit.command.TabCompleter
//import org.bukkit.entity.Player
import luna.nodes.Message
import luna.nodes.Nodes
import luna.nodes.objects.TerritoryId
//import luna.nodes.utils.string.filterByStart
//import luna.nodes.utils.string.filterNation
//import luna.nodes.utils.string.filterResident
//import luna.nodes.utils.string.filterTown

class NodesCommand : Command("nodes", "nd") {
    init {
        // no args, print plugin info
        setDefaultExecutor { sender, context ->
            Message.print(sender, "${ChatColor.BOLD}Nodes ${Nodes.VERSION}")

            // print number of resource nodes and territories loaded
            Message.print(sender, "World info:")
            Message.print(sender, "- Resource Nodes${ChatColor.WHITE}: ${Nodes.getResourceNodeCount()}")
            Message.print(sender, "- Territories${ChatColor.WHITE}: ${Nodes.getTerritoryCount()}")
            Message.print(sender, "- Residents${ChatColor.WHITE}: ${Nodes.getResidentCount()}")
            Message.print(sender, "- Towns${ChatColor.WHITE}: ${Nodes.getTownCount()}")
            Message.print(sender, "- Nations${ChatColor.WHITE}: ${Nodes.getNationCount()}")

            Message.print(sender, "Use \"/nodes help\" to see subcommands")
        }

        addSubcommand(NodesHelpCommand())
        addSubcommand(NodesResourceCommand())
        addSubcommand(NodesTerritoryCommand())
        addSubcommand(NodesTownCommand())
        addSubcommand(NodesNationCommand())
        addSubcommand(NodesPlayerCommand())
    }
}

class NodesHelpCommand : Command("help") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "${ChatColor.BOLD}[Nodes] World info commands:")
            Message.print(sender, "/nodes resource${ChatColor.WHITE}: Get a resource node's properties")
            Message.print(sender, "/nodes territory${ChatColor.WHITE}: Get territory info")
            Message.print(sender, "/nodes town${ChatColor.WHITE}: Get town info")
            Message.print(sender, "/nodes nation${ChatColor.WHITE}: Get nation info")
            Message.print(sender, "/nodes player${ChatColor.WHITE}: Get player info")
            Message.print(sender, "/nodes war${ChatColor.WHITE}: Current war status")
        }
    }
}

class NodesResourceCommand : Command("resource") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodes resource [name]")
        }

        val resourceArg = ArgumentType.String("name")

        addSyntax({ sender, context ->
            Message.print(sender, "${ChatColor.BOLD}Resource nodes:")
            for (v in Nodes.resourceNodes.values) {
                Message.print(sender, "- ${v.name}")
            }
            Message.print(sender, "Use \"/nodes resource [name]\" to get more info")
        })

        addSyntax({ sender, context ->
            // parse resource node name
            val resource = Nodes.resourceNodes.get(context[resourceArg])
            if (resource != null) {
                resource.printInfo(sender)
            } else {
                Message.error(sender, "Invalid resource node \"${context[resourceArg]}\"")
            }
        },resourceArg)
    }
}

class NodesTerritoryCommand : Command("territory") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodes territory [id]")
        }

        val territoryArg = ArgumentType.Integer("id")

        addSyntax({ sender, context ->
            // if command sender was player, print territory info of current location
            val player = sender as? Player
            if (player == null) {
                return@addSyntax
            }

            val loc = player.position
            val territory = Nodes.getTerritoryFromBlock(loc.x.toInt(), loc.z.toInt())

            Message.print(sender, "Territory at current location:")
            Message.print(sender, "(Other usage: \"/nodes territory [id]\")")

            if (territory == null) {
                Message.error(sender, "No territory at current location")
                return@addSyntax
            }

            // print territory info
            territory.printInfo(sender)
            territory.printResources(sender)
        })

        addSyntax({ sender, context ->
            // if command sender was player, print territory info of current location
            val player = sender as? Player
            if (player == null) {
                return@addSyntax
            }

            // try parse input as id, then try to get territory with that id
            val territory = context[territoryArg].let { id -> Nodes.territories[TerritoryId(id)] }
            if (territory == null) {
                Message.error(sender, "Invalid territory id \"${context[territoryArg]}\"")
                return@addSyntax
            }

            // print territory info
            territory.printInfo(sender)
            territory.printResources(sender)
        }, territoryArg)
    }
}

class NodesTownCommand : Command("town", "towns") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodes town [name]")
        }

        val townArg = ArgumentType.String("name")

        addSyntax({ sender, context ->
            Message.print(sender, "${ChatColor.BOLD}Towns (${Nodes.getTownCount()}): Showing [Players] [Territories]")
            val towns = Nodes.towns.values.toMutableList()
            towns.sortByDescending { it.residents.size }

            for (t in towns) {
                Message.print(sender, "- ${t.name}${ChatColor.WHITE}: ${t.residents.size}P ${t.territories.size}T")
            }

            Message.print(sender, "Use \"/nodes town [name]\" to get a town's info")
        })

        addSyntax( { sender, context ->
            val town = Nodes.towns.get(context[townArg])
            if (town != null) {
                town.printInfo(sender)
            } else {
                Message.error(sender, "Invalid town name \"${context[townArg]}\"")
            }
        }, townArg)
    }
}

class NodesNationCommand : Command("nation", "nations") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodes nation [name]")
        }

        val nationArg = ArgumentType.String("name")

        addSyntax({ sender, context ->
            Message.print(sender, "${ChatColor.BOLD}Nations (${Nodes.getNationCount()}):")
            val nations = Nodes.nations.values.toMutableList()
            nations.sortBy { it.name }

            for (n in nations) {
                Message.print(sender, "- ${n.name}${ChatColor.WHITE}")
            }

            Message.print(sender, "Use \"/nodes nation [name]\" to get a nation's info")
        })

        addSyntax( { sender, context ->
            val nation = Nodes.nations.get(context[nationArg])
            if (nation != null) {
                nation.printInfo(sender)
            } else {
                Message.error(sender, "Invalid nation name \"${context[nationArg]}\"")
            }
        }, nationArg)
    }
}

class NodesPlayerCommand : Command("player", "players") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodes player [name]")
        }

        val residentArg = ArgumentType.String("name")

        addSyntax({ sender, context ->
            val resident = Nodes.getResidentFromName(context[residentArg])
            if (resident != null) {
                resident.printInfo(sender)
            } else {
                Message.error(sender, "Invalid player name \"${context[residentArg]}\"")
            }
        }, residentArg)
    }
}
