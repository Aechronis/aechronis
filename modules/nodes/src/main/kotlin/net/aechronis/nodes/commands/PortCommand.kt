/**
 * Port commands
 * /port [command]
 */

package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.commands.arguments.ArgumentPort
import net.aechronis.nodes.constants.DiplomaticRelationship
import net.aechronis.nodes.objects.Command
import net.aechronis.nodes.tasks.PortWarpTask
import net.aechronis.nodes.utils.ChatColor

class PortCommand : Command("port") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "${ChatColor.BOLD}[Nodes] Port Commands:")
            Message.print(player, "/port list${ChatColor.WHITE}: List all ports")
            Message.print(player, "/port info${ChatColor.WHITE}: Print info about a port")
            Message.print(player, "/port warp${ChatColor.WHITE}: Warp to a port within the source port's tier range")
        }

        addSubcommand(PortListCommand())
        addSubcommand(PortInfoCommand())
        addSubcommand(PortWarpCommand())
    }
}

class PortListCommand : Command("list") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /port list")
        }

        addSyntax({ player, resident, context ->
            Message.print(player, "${ChatColor.BOLD}List of ports:")
            for (port in Nodes.ports) {
                val status = if (port.isPublic) "(public)" else "(owned)"
                Message.print(player, "- ${port.name} ${ChatColor.GRAY}T${port.tier} $status")
            }
        })
    }
}

class PortInfoCommand : Command("info") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /port info <port-name>")
        }

        val portArg = ArgumentPort.create("port-name")

        addSyntax({ player, resident, context ->
            context[portArg].printInfo(player)
        }, portArg)
    }
}

class PortWarpCommand : Command("warp") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /port warp <port-name>")
        }

        val portArg = ArgumentPort.create("port-name")

        addSyntax({ player, resident, context ->
            // check if player is already warping
            if (Nodes.playerWarpTasks.contains(player)) {
                Message.print(player, "${ChatColor.RED}You are already warping somewhere")
                return@addSyntax
            }

            // get port player is at
            val sourceBuilding = Nodes.chunkToBuilding.get(
                listOf(
                    Math.floorDiv(player.position.blockX(), 16),
                    Math.floorDiv(player.position.blockZ(), 16),
                ),
            )
            val source = sourceBuilding as? net.aechronis.nodes.objects.Port
            if (source === null) {
                Message.error(player, "You must be in the same chunk as a port to warp")
                return@addSyntax
            }

            val dest = context[portArg]

            // check if port is same
            if (source === dest) {
                Message.error(player, "You are already at this port...")
                return@addSyntax
            }

            // check destination is within the source port's tier range
            val maxDist = source.maxWarpDistance
            val maxDistSq: Long = maxDist.toLong() * maxDist.toLong()
            val dx = (source.chunkX - dest.chunkX).toLong() * 16L
            val dz = (source.chunkZ - dest.chunkZ).toLong() * 16L
            if (dx * dx + dz * dz > maxDistSq) {
                Message.error(player, "Port ${dest.name} is out of range for a tier ${source.tier} port (max $maxDist blocks)")
                return@addSyntax
            }

            // check port access
            if (!dest.isPublic) {
                val owner = Nodes.getPortOwner(dest)
                if (owner !== null) {
                    val relation = Nodes.getRelationshipOfPlayerToTown(player, owner)

                    // Only allow allies (town members, nation members, and allies) to use the port
                    val canAccess: Boolean = when (relation) {
                        DiplomaticRelationship.TOWN,
                        DiplomaticRelationship.NATION,
                        DiplomaticRelationship.ALLY,
                        -> true
                        else -> false
                    }

                    if (!canAccess) {
                        Message.error(player, "Port ${dest.name}'s owner ${owner.name} only allows allies to warp (you are $relation)")
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
                dest,
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
