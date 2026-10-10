package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.commands.arguments.ArgumentTown
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.entity.Player

class TownCommand : NodesCommand("t", null, "town") {
    init {
        setDefaultExecutor { player, resident, context ->
            printTownHelp(player)
        }

        // no args, print current town info
        addSyntax({ player, resident, context ->
            if (resident.town != null) {
                resident.town!!.printInfo(player)
            } else {
                printTownHelp(player)
            }
        })

        addSubcommand(TownHelpCommand())
        addSubcommand(TownPromoteCommand())
        addSubcommand(TownDemoteCommand())
        addSubcommand(TownApplyCommand())
        addSubcommand(TownInviteCommand())
        addSubcommand(TownAcceptCommand())
        addSubcommand(TownDenyCommand())
        addSubcommand(TownLeaveCommand())
        addSubcommand(TownKickCommand())
        addSubcommand(TownSpawn())
        addSubcommand(TownSetSpawn())
        addSubcommand(TownListCommand())
        addSubcommand(TownInfoCommand())
        addSubcommand(TownOnlineCommand())
        addSubcommand(TownIncomeCommand())
        addSubcommand(TownBuildingsCommand())
        addSubcommand(TownPermissionsCommand())
        addSubcommand(TownProtectCommand())
        addSubcommand(TownTrustCommand())
        addSubcommand(TownUntrustCommand())
        addSubcommand(TownFlyCommand())
        addSubcommand(TownMinimapCommand())
        addSubcommand(TownPlotCommand())
    }
}

class TownHelpCommand : NodesCommand("help") {
    init {
        setDefaultExecutor { player, resident, context ->
            printTownHelp(player)
        }
    }
}

class TownListCommand : NodesCommand("list") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /town list")
        }

        addSyntax({ player, resident, context ->
            Message.print(player, "${ChatColor.BOLD}Town - Population")
            val townsList = ArrayList(Town.all())
            townsList.sortByDescending { it.residents.size }
            townsList.forEach { town ->
                Message.print(player, "${town.name}${ChatColor.WHITE} - ${town.residents.size}")
            }
        })
    }
}

class TownInfoCommand : NodesCommand("info") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage:")
            Message.print(player, "/town info")
            Message.print(player, "/town info <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, town, context ->
            town.printInfo(player)
        })

        addSyntax({ player, resident, context ->
            context[townArg].printInfo(player)
        }, townArg)
    }
}

class TownOnlineCommand : NodesCommand("online") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage:")
            Message.print(player, "/town online")
            Message.print(player, "/town online <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, town, context ->
            val numPlayersOnline = town.playersOnline.size
            val playersOnline = town.playersOnline.joinToString(", ", transform = { p -> p.username })
            Message.print(player, "Players online in town ${town.name} [$numPlayersOnline]: ${ChatColor.WHITE}$playersOnline")
        })

        addSyntax({ player, resident, context ->
            val numPlayersOnline = context[townArg].playersOnline.size
            val playersOnline = context[townArg].playersOnline.joinToString(", ", transform = { p -> p.username })
            Message.print(player, "Players online in town ${context[townArg].name} [$numPlayersOnline]: ${ChatColor.WHITE}$playersOnline")
        }, townArg)
    }
}

private fun printTownHelp(sender: Player) {
    Message.print(sender, "${ChatColor.BOLD}[Nodes] Town commands:")
    Message.print(sender, "/town promote${ChatColor.WHITE}: Give officer rank to resident")
    Message.print(sender, "/town demote${ChatColor.WHITE}: Remove officer rank from resident")
    Message.print(sender, "/town apply${ChatColor.WHITE}: Apply to join a town")
    Message.print(sender, "/town invite${ChatColor.WHITE}: Invite a player to your town")
    Message.print(sender, "/town leave${ChatColor.WHITE}: Leave your town")
    Message.print(sender, "/town kick${ChatColor.WHITE}: Kick player from your town")
    Message.print(sender, "/town spawn${ChatColor.WHITE}: Teleport to your town spawnpoint")
    Message.print(sender, "/town setspawn${ChatColor.WHITE}: Set a new town spawnpoint")
    Message.print(sender, "/town list${ChatColor.WHITE}: List all towns")
    Message.print(sender, "/town info${ChatColor.WHITE}: View town details")
    Message.print(sender, "/town online${ChatColor.WHITE}: View town's online players")
    Message.print(sender, "/town income${ChatColor.WHITE}: Open town income")
    Message.print(sender, "/town buildings${ChatColor.WHITE}: View buildings and manage production")
    Message.print(sender, "/town income info${ChatColor.WHITE}: View income rates per income tick")
    Message.print(sender, "/town permissions${ChatColor.WHITE}: Set town protection permissions")
    Message.print(sender, "/town protect${ChatColor.WHITE}: Protect town chests")
    Message.print(sender, "/town trust${ChatColor.WHITE}: Mark player as trusted")
    Message.print(sender, "/town untrust${ChatColor.WHITE}: Remove player from trusted")
    Message.print(sender, "/town fly${ChatColor.WHITE}: Fly inside your town or nation")
    Message.print(sender, "/town minimap${ChatColor.WHITE}: Configure the resource map")
    Message.print(sender, "/town plot${ChatColor.WHITE}: Protect areas with 3D plots")
}
