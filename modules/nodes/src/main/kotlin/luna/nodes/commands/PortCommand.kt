/**
 * Port commands
 * /port [command]
 */

package luna.nodes.commands

import luna.nodes.Message
import luna.nodes.Nodes
import luna.nodes.commands.arguments.ArgumentPort
import luna.nodes.constants.DiplomaticRelationship
import luna.nodes.objects.Command
import luna.nodes.tasks.PortWarpTask
import luna.nodes.utils.ChatColor

class PortCommand : Command("port") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "${ChatColor.BOLD}[Nodes] Port Commands:")
            Message.print(sender, "/port list${ChatColor.WHITE}: List all ports")
            Message.print(sender, "/port info${ChatColor.WHITE}: Print info about a port")
            Message.print(sender, "/port warp${ChatColor.WHITE}: Warp to a port")
        }

        addSubcommand(PortListCommand())
        addSubcommand(PortInfoCommand())
        addSubcommand(PortWarpCommand())
    }
}

class PortListCommand : Command("list") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /port list")
        }

        addSyntax({ player, resident, context ->
            Message.print(player, "${ChatColor.BOLD}List of ports:")
            for (group in Nodes.portGroups.values) {
                Message.print(player, "${group.name}:")
                for (port in Nodes.ports.values) {
                    if (port.groups.contains(group)) {
                        // comma-separated group names for this port
                        val groupNames = port.groups.joinToString(", ") { it.name }

                        val status = if (port.isPublic) {
                            "(public)"
                        } else {
                            "(owned)"
                        }

                        Message.print(player, "- ${port.name} ${ChatColor.GRAY}- $groupNames $status")
                    }
                }
            }
        })
    }
}

class PortInfoCommand : Command("info") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /port info <port-name>")
        }

        val portArg = ArgumentPort.create("port-name")

        addSyntax( { player, resident, context ->
            context[portArg].printInfo(player)
        }, portArg)
    }
}

class PortWarpCommand : Command("warp") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /port warp <port-name>")
        }

        val portArg = ArgumentPort.create("port-name")

        addSyntax( { player, resident, context ->
            // check if player is already warping
            if (Nodes.playerWarpTasks.contains(player)) {
                Message.print(player, "${ChatColor.RED}You are already warping somewhere")
                return@addSyntax
            }

            // get port player is at
            val source = Nodes.chunkToPort.get(listOf(Math.floorDiv(player.position.blockX(), 16), Math.floorDiv(player.position.blockZ(), 16)))
            if (source === null) {
                Message.error(player, "You must be in the same chunk as a port to warp")
                return@addSyntax
            }

            // check if port is same
            if (source === context[portArg]) {
                Message.error(player, "You are already at this port...")
                return@addSyntax
            }

            // verify ports share groups
            if (!Nodes.sharePortGroups(source, context[portArg])) {
                Message.error(player, "These ports are not in the same region group...")
                return@addSyntax
            }

            // check port access
            if (!context[portArg].isPublic) {
                val owner = Nodes.getPortOwner(context[portArg])
                if (owner !== null) {
                    val relation = Nodes.getRelationshipOfPlayerToTown(player, owner)

                    // Only allow allies (town members, nation members, and allies) to use the port
                    val canAccess: Boolean = when (relation) {
                        DiplomaticRelationship.TOWN,
                        DiplomaticRelationship.NATION,
                        DiplomaticRelationship.ALLY, -> true
                        else -> false
                    }

                    if (!canAccess) {
                        Message.error(player, "Port ${context[portArg].name}'s owner ${owner.name} only allows allies to warp (you are $relation)")
                        return@addSyntax
                    }
                }
            }

            val vehicle = player.vehicle
            if (vehicle == null || !vehicle.entityType.toString().contains("_boat")) {
                Message.error(player, "You must be in a boat or a ship vehicle to warp to ports")
                return@addSyntax
            }

            // do warp
            val task = PortWarpTask(
                player,
                player.position,
                context[portArg],
                Nodes.config.portWarpTime,
            )

            // run asynchronous warp timer
            Nodes.playerWarpTasks.put(
                player,
                task.start(),
            )
        }, portArg)
    }
}
