package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.TrainStation
import net.aechronis.nodes.objects.Trains
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.coordinate.Pos

class TrainCommand : NodesCommand("trains") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "${ChatColor.BOLD}[Nodes] Train Commands:")
            Message.print(player, "/trains create${ChatColor.WHITE}: Create a tier 0 station on the gold block you are looking at")
        }

        val create = ArgumentType.Literal("create")
        addSyntax({ player, _, _ ->
            val position = player.getTargetBlockPosition(5)?.asBlockVec()
            if (position == null) {
                Message.error(player, "You must look at a gold block")
                return@addSyntax
            }
            Trains.create(position, player.instance).onSuccess { station ->
                Message.print(player, "Created tier 0 station ${station.id}")
            }.onFailure { error ->
                Message.error(player, error.message ?: "Failed to create station")
            }
        }, create)
    }
}

class NodesAdminTrainsCommand : NodesCommand("trains", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "${ChatColor.BOLD}[Nodes] Train Admin Commands:")
            Message.print(player, "/nda trains scan <station-id>${ChatColor.WHITE}: Rescan one station")
            Message.print(player, "/nda trains scanall${ChatColor.WHITE}: Rescan all stations")
            Message.print(player, "/nda trains tp <station-id>${ChatColor.WHITE}: Teleport to a station")
            Message.print(player, "/nda trains ban|unban <station-id>${ChatColor.WHITE}: Change station availability")
            Message.print(player, "/nda trains settier <station-id> <0-3>${ChatColor.WHITE}: Set station quality tier")
            Message.print(player, "/nda trains list${ChatColor.WHITE}: List stations")
            Message.print(player, "/nda trains remove <station-id>${ChatColor.WHITE}: Remove a station")
        }

        val scan = ArgumentType.Literal("scan")
        val scanAll = ArgumentType.Literal("scanall")
        val teleport = ArgumentType.Literal("tp")
        val ban = ArgumentType.Literal("ban")
        val unban = ArgumentType.Literal("unban")
        val setTier = ArgumentType.Literal("settier")
        val list = ArgumentType.Literal("list")
        val remove = ArgumentType.Literal("remove")
        val station = stationArgument("station-id")
        val tier = tierArgument("tier")

        addSyntax({ player, _, context ->
            Trains.scan(context[station].id, player.instance).onSuccess { count ->
                Message.print(player, "Scanned station ${context[station].id}: found $count connection(s)")
            }.onFailure { error ->
                Message.error(player, error.message ?: "Failed to scan station")
            }
        }, scan, station)

        addSyntax({ player, _, _ ->
            val count = Trains.scanAll(player.instance)
            Message.print(player, "Rescanned all stations: found $count connection(s)")
        }, scanAll)

        addSyntax({ player, _, context ->
            val target = context[station]
            player.teleport(Pos(target.position.x() + 0.5, target.position.y() + 1.0, target.position.z() + 0.5, player.position.yaw, player.position.pitch))
            Message.print(player, "Teleported to station ${target.id}")
        }, teleport, station)

        addSyntax({ player, _, context ->
            val target = context[station]
            Trains.setBanned(target.id, true).onSuccess {
                Message.print(player, "Station ${target.id} banned")
            }.onFailure { error ->
                Message.error(player, error.message ?: "Failed to ban station")
            }
        }, ban, station)

        addSyntax({ player, _, context ->
            val target = context[station]
            Trains.setBanned(target.id, false).onSuccess {
                Message.print(player, "Station ${target.id} unbanned")
            }.onFailure { error ->
                Message.error(player, error.message ?: "Failed to unban station")
            }
        }, unban, station)

        addSyntax({ player, _, context ->
            val target = context[station]
            val value = context[tier]
            Trains.setTier(target.id, value).onSuccess {
                Message.print(player, "Station ${target.id} set to tier $value")
            }.onFailure { error ->
                Message.error(player, error.message ?: "Failed to set station tier")
            }
        }, setTier, station, tier)

        addSyntax({ player, _, _ ->
            val stations = Trains.allStations()
            if (stations.isEmpty()) {
                Message.print(player, "No train stations")
                return@addSyntax
            }
            Message.print(player, "${ChatColor.BOLD}Train stations:")
            stations.forEach { station ->
                val status = if (station.banned) "banned" else "active"
                Message.print(player, "- ${station.id}: tier ${station.tier}, $status, ${Trains.edgesFrom(station.id).size} connection(s)")
            }
        }, list)

        addSyntax({ player, _, context ->
            val station = context[station]
            Trains.remove(station.id)
            Message.print(player, "Removed station ${station.id}")
        }, remove, station)
    }
}

private fun stationArgument(id: String): Argument<TrainStation> {
    val argument = ArgumentType.Word(id)
    argument.setSuggestionCallback { _, _, suggestion ->
        val input = suggestion.input.substringAfterLast(" ")
        Trains.allStations()
            .asSequence()
            .map { it.id.toString() }
            .filter { it.startsWith(input) }
            .forEach { suggestion.addEntry(SuggestionEntry(it)) }
    }
    return argument.map { input ->
        input.toIntOrNull()?.let(Trains::station)
            ?: throw ArgumentSyntaxException("Station not found", input, 1)
    }
}

private fun tierArgument(id: String): Argument<Int> {
    val argument = ArgumentType.Word(id)
    argument.setSuggestionCallback { _, _, suggestion ->
        val input = suggestion.input.substringAfterLast(" ")
        (0..3)
            .map(Int::toString)
            .filter { it.startsWith(input) }
            .forEach { suggestion.addEntry(SuggestionEntry(it)) }
    }
    return argument.map { input ->
        input.toIntOrNull()?.takeIf { it in 0..3 }
            ?: throw ArgumentSyntaxException("Tier must be between 0 and 3", input, 1)
    }
}
