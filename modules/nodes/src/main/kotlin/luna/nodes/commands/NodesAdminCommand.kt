/**
 * Admin commands to manage world
 * - modify towns, nations
 * - war enable/disable
 *
 *    /nodesadmin command ...
 *    /nda command
 */

package luna.nodes.commands

import luna.nodes.Message
import luna.nodes.Nodes
import luna.nodes.commands.arguments.ArgumentResident
import luna.nodes.commands.arguments.ArgumentResidentArray
import luna.nodes.commands.arguments.ArgumentSanitizedString
import luna.nodes.commands.arguments.ArgumentTerritory
import luna.nodes.commands.arguments.ArgumentTerritoryArray
import luna.nodes.commands.arguments.ArgumentTown
import luna.nodes.commands.arguments.ArgumentTownArray
import luna.nodes.objects.Command
import luna.nodes.utils.ChatColor
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.minestom.server.adventure.audience.Audiences

class NodesAdminCommand : Command("nodesadmin", "nda") {
    init{
        setDefaultExecutor { sender, context ->
            Message.print(sender, "[Nodes] Admin commands:")
            Message.print(sender, "/nodesadmin war${ChatColor.WHITE}: Enable/disable war")
            Message.print(sender, "/nodesadmin town${ChatColor.WHITE}: Manage towns (see \"/nodesadmin town help\")")
            Message.print(sender, "/nodesadmin nation${ChatColor.WHITE}: Manage nations (see \"/nodesadmin nation help\")")
            Message.print(sender, "/nodesadmin port${ChatColor.WHITE}: Manage ports (see \"/nodesadmin port help\")")
            Message.print(sender, "/nodesadmin portgroup${ChatColor.WHITE}: Manage port groups (see \"/nodesadmin portgroup help\")")
            Message.print(sender, "/nodesadmin enemy${ChatColor.WHITE}: Make two towns/nations enemies")
            Message.print(sender, "/nodesadmin ally${ChatColor.WHITE}: Sets alliance between two towns/nations")
            Message.print(sender, "/nodesadmin allyremove${ChatColor.WHITE}: Removes alliance between two towns/nations")
            Message.print(sender, "/nodesadmin save${ChatColor.WHITE}: Force save world")
            Message.print(sender, "/nodesadmin load${ChatColor.WHITE}: Force load world")
            Message.print(sender, "/nodesadmin runincome${ChatColor.WHITE}: Runs income for all towns")
            Message.print(sender, "/nodesadmin debug${ChatColor.WHITE}: World object debugger")
        }

        addSubcommand(NodesAdminHelpCommand())
        addSubcommand(NodesAdminWarCommand())
        addSubcommand(NodesAdminTownCommand())
//        addSubcommand(NodesAdminNationCommand())
//        addSubcommand(NodesAdminPortCommand())
//        addSubcommand(NodesAdminPortGroupCommand())
//        addSubcommand(NodesAdminEnemyCommand())
//        addSubcommand(NodesAdminAllyCommand())
//        addSubcommand(NodesAdminAllyRemoveCommand())
//        addSubcommand(NodesAdminSaveCommand())
//        addSubcommand(NodesAdminLoadCommand())
//        addSubcommand(NodesAdminRunIncomeCommand())
//        addSubcommand(NodesAdminDebugCommand())
    }
}

class NodesAdminHelpCommand() : Command("help") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "[Nodes] Admin commands:")
            Message.print(sender, "/nodesadmin war${ChatColor.WHITE}: Enable/disable war")
            Message.print(sender, "/nodesadmin town${ChatColor.WHITE}: Manage towns (see \"/nodesadmin town help\")")
            Message.print(sender, "/nodesadmin nation${ChatColor.WHITE}: Manage nations (see \"/nodesadmin nation help\")")
            Message.print(sender, "/nodesadmin port${ChatColor.WHITE}: Manage ports (see \"/nodesadmin port help\")")
            Message.print(sender, "/nodesadmin portgroup${ChatColor.WHITE}: Manage port groups (see \"/nodesadmin portgroup help\")")
            Message.print(sender, "/nodesadmin enemy${ChatColor.WHITE}: Make two towns/nations enemies")
            Message.print(sender, "/nodesadmin ally${ChatColor.WHITE}: Sets alliance between two towns/nations")
            Message.print(sender, "/nodesadmin allyremove${ChatColor.WHITE}: Removes alliance between two towns/nations")
            Message.print(sender, "/nodesadmin save${ChatColor.WHITE}: Force save world")
            Message.print(sender, "/nodesadmin load${ChatColor.WHITE}: Force load world")
            Message.print(sender, "/nodesadmin runincome${ChatColor.WHITE}: Runs income for all towns")
            Message.print(sender, "/nodesadmin debug${ChatColor.WHITE}: World object debugger")
        }
    }
}

class NodesAdminWarCommand() : Command("war") {
    init {
        setDefaultExecutor { sender, context ->
            Nodes.war.printInfo(sender, true)
            Message.print(sender, "Toggle state: \"/nodesadmin war [enable|disable|skirmish]\"")
        }

        addSubcommand(NodesAdminWarEnableCommand())
        addSubcommand(NodesAdminWarDisableCommand())
        addSubcommand(NodesAdminWarSkirmishCommand())

    }
}

class NodesAdminWarEnableCommand() : Command("enable") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin war enable")
        }

        addSyntax({ player, resident, context ->
            Nodes.enableWar(true, false, true)
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled")

            // play MENACING wither spawn sound
            Audiences.all().playSound(Sound.sound(Key.key("entity.wither.spawn"), Sound.Source.PLAYER, 1.0f, 1.0f))
        })
    }
}

class NodesAdminWarDisableCommand() : Command("disable") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin war disable")
        }

        addSyntax({ player, resident, context ->
            if (Nodes.war.enabled) {
                Nodes.disableWar()
                Message.broadcast("${ChatColor.BOLD}Nodes war disabled")
            } else {
                Message.error(player, "Nodes war already disabled")
            }
        })
    }
}

class NodesAdminWarSkirmishCommand() : Command("skirmish") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin war skirmish")
        }

        addSyntax({ player, resident, context ->
            Nodes.enableWar(false, true, Nodes.config.allowDestructionDuringSkirmish)
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes border skirmishes enabled")

            // play MENACING wither spawn sound
            Audiences.all().playSound(Sound.sound(Key.key("entity.wither.spawn"), Sound.Source.PLAYER, 1.0f, 1.0f))
        })
    }
}

class NodesAdminTownCommand() : Command("town") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "${ChatColor.BOLD}[Nodes] Admin town management:")
            Message.print(sender, "/nodesadmin town create${ChatColor.WHITE}: Create a new town")
            Message.print(sender, "/nodesadmin town delete${ChatColor.WHITE}: Delete existing town")
            Message.print(sender, "/nodesadmin town addplayer${ChatColor.WHITE}: Add players to town")
            Message.print(sender, "/nodesadmin town removeplayer${ChatColor.WHITE}: Remove players from town")
            Message.print(sender, "/nodesadmin town addterritory${ChatColor.WHITE}: Add territories to town")
            Message.print(sender, "/nodesadmin town removeterritory${ChatColor.WHITE}: Remove territories from town")
            Message.print(sender, "/nodesadmin town captureterritory${ChatColor.WHITE}: Add captured territories to town")
            Message.print(sender, "/nodesadmin town releaseterritory${ChatColor.WHITE}: Release captured territories")
            Message.print(sender, "/nodesadmin town setspawn${ChatColor.WHITE}: Set town's spawn to location")
            Message.print(sender, "/nodesadmin town spawn${ChatColor.WHITE}: Go to town's spawn")
            Message.print(sender, "/nodesadmin town addofficer${ChatColor.WHITE}: Add officer to town")
            Message.print(sender, "/nodesadmin town removeofficer${ChatColor.WHITE}: Remove officer from town")
            Message.print(sender, "/nodesadmin town leader${ChatColor.WHITE}: Set town leader to player")
            Message.print(sender, "/nodesadmin town open${ChatColor.WHITE}: Toggle town is open to join")
            Message.print(sender, "/nodesadmin town income${ChatColor.WHITE}: View a town's income inventory")
            Message.print(sender, "Run a command with no args to see usage.")
        }

        addSubcommand(NodesAdminTownCreateCommand())
        addSubcommand(NodesAdminTownDeleteCommand())
        addSubcommand(NodesAdminTownAddPlayerCommand())
        addSubcommand(NodesAdminTownRemovePlayerCommand())
        addSubcommand(NodesAdminTownAddTerritoryCommand())
        addSubcommand(NodesAdminTownRemoveTerritoryCommand())
        addSubcommand(NodesAdminTownCaptureTerritoryCommand())
        addSubcommand(NodesAdminTownReleaseTerritoryCommand())
        addSubcommand(NodesAdminTownAddOfficerCommand())
        addSubcommand(NodesAdminTownRemoveOfficerCommand())
        addSubcommand(NodesAdminTownLeaderCommand())
        addSubcommand(NodesAdminTownRemoveLeaderCommand())
        addSubcommand(NodesAdminTownIncomeCommand())
        addSubcommand(NodesAdminTownSetHomeCommand())
        addSubcommand(NodesAdminTownDefaultTownSpawnsCommand())
    }
}

class NodesAdminTownCreateCommand() : Command("create") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town create <town-name> <territory-ids>")
        }

        val townArg = ArgumentSanitizedString.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax( {player, resident, context ->
            // first territory is new town home
            val town = Nodes.createTown(context[townArg], context[territoriesArg][0], null).getOrElse({ err ->
                Message.error(player, "Failed to create town: ${err.message}")
                return@addSyntax
            })

            // add the other territories
            for (i in 1 until context[territoriesArg].size) {
                Nodes.addTerritoryToTown(town, context[territoriesArg][i])
            }

            Message.print(player, "Created town \"${context[townArg]}\" with ${context[territoriesArg].size} territories")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownDeleteCommand() : Command("delete") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town delete <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax( {player, resident, context ->
            Nodes.destroyTown(context[townArg])
            Message.print(player, "Town \"${context[townArg].name}\" has been deleted")
        }, townArg)
    }
}

class NodesAdminTownAddPlayerCommand() : Command("addplayer") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town addplayer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSyntax( {player, resident, context ->
            for (resident in context[playersArg]) {
                Nodes.addResidentToTown(context[townArg], resident)
                Message.print(player, "Added \"${resident.name}\" to town \"${context[townArg].name}\"")
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownRemovePlayerCommand() : Command("removeplayer") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town removeplayer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSyntax( {player, resident, context ->
            for (resident in context[playersArg]) {
                Nodes.removeResidentFromTown(context[townArg], resident)
                Message.print(player, "Removed \"${resident.name}\" from town \"${context[townArg].name}\"")
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownAddTerritoryCommand() : Command("addterritory") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town addterritory <town-name> <territory-ids>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax( {player, resident, context ->
            // add territories
            for (terr in context[territoriesArg]) {
                Nodes.addTerritoryToTown(context[townArg], terr)
            }

            Message.print(player, "Added ${context[territoriesArg].size} territories to town \"${context[townArg].name}\"")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownRemoveTerritoryCommand() : Command("removeterritory") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town removeterritory <town-name> <territory-ids>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax( {player, resident, context ->
            // remove territories
            for (terr in context[territoriesArg]) {
                Nodes.unclaimTerritory(context[townArg], terr)
            }

            Message.print(player, "Removed ${context[territoriesArg].size} territories from town \"${context[townArg].name}\"")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownCaptureTerritoryCommand() : Command("captureterritory") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town captureterritory <town-name> <territory-ids>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax( {player, resident, context ->
            // add territories
            for (terr in context[territoriesArg]) {
                Nodes.captureTerritory(context[townArg], terr)
            }

            Message.print(player, "Captured ${context[territoriesArg].size} territories for town \"${context[townArg].name}\"")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownReleaseTerritoryCommand() : Command("releaseterritory") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town releaseterritory <territory-ids>")
        }

        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax( {player, resident, context ->
            // add territories
            for (terr in context[territoriesArg]) {
                Nodes.releaseTerritory(terr)
            }

            Message.print(player, "Released ${context[territoriesArg].size} territories under occupation")
        }, territoriesArg)
    }
}

class NodesAdminTownAddOfficerCommand() : Command("addofficer") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town addofficer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSyntax( {player, resident, context ->
            // make residents officers
            for (r in context[playersArg]) {
                Nodes.townAddOfficer(context[townArg], r)
                Message.print(player, "Made \"${r.name}\" officer of \"${context[townArg].name}\"")
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownRemoveOfficerCommand() : Command("removeofficer") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town removeofficer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSyntax( {player, resident, context ->
            // make residents officers
            for (r in context[playersArg]) {
                Nodes.townRemoveOfficer(context[townArg], r)
                Message.print(player, "Removed \"${r.name}\" as officer of \"${context[townArg].name}\"")
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownLeaderCommand() : Command("leader") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town leader <town-name> <player-name>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playerArg = ArgumentResident.create("player-name")

        addSyntax( {player, resident, context ->
            if (resident.town !== context[townArg]) {
                Message.error(player, "Player \"${context[playerArg].name}\" is not a member of \"${context[townArg].name}\"")
                return@addSyntax
            }

            Nodes.townSetLeader(context[townArg], resident)
            Message.print(player, "Player \"${context[playerArg].name}\" is now leader of \"${context[townArg].name}\"")
        }, townArg, playerArg)
    }
}

class NodesAdminTownRemoveLeaderCommand() : Command("removeleader") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town removeleader <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax( {player, resident, context ->
            Nodes.townSetLeader(context[townArg], null)
            Message.print(player, "Removed leader of \"${context[townArg].name}\"")
        }, townArg)
    }
}

class NodesAdminTownIncomeCommand() : Command("income") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town income <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax( {player, resident, context ->
            // open town inventory
            player.openInventory(Nodes.getTownIncomeInventory(context[townArg]))
        }, townArg)
    }
}

class NodesAdminTownSetSpawnCommand() : Command("setspawn") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town setspawn <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax( {player, resident, context ->
            val result = Nodes.setTownSpawn(context[townArg], player.position)

            if (result == true) {
                Message.print(player, "Town \"${context[townArg].name}\" spawn set to current location")
            } else {
                Message.error(player, "Spawn location must be within town's home territory")
            }
        }, townArg)
    }
}

class NodesAdminTownSpawnCommand() : Command("spawn") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town spawn <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax( {player, resident, context ->
            player.teleport(context[townArg].spawnpoint)
        }, townArg)
    }
}

class NodesAdminTownSetHomeCommand() : Command("sethome") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town sethome <town-name> <territory-id>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoryArg = ArgumentTerritory.create("territory-id")

        addSyntax( {player, resident, context ->
            // set town home territory
            if (context[townArg] !== context[territoryArg].town) {
                Message.error(player, "Invalid territory id=${context[territoryArg].id}: does not belong to town")
                return@addSyntax
            }

            if (context[townArg].home == context[territoryArg].id) {
                Message.error(player, "Invalid territory id=${context[territoryArg].id}: already is home territory")
                return@addSyntax
            }

            Nodes.setTownHomeTerritory(context[townArg], context[territoryArg])
            Message.print(player, "Moved \"${context[townArg].name}\" home territory to id = ${context[territoryArg].id}")
        }, townArg)
    }
}

class NodesAdminTownDefaultTownSpawnsCommand() : Command("defaulttownspawns") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town defaulttownspawns <town-names>")
        }

        val townsArg = ArgumentTownArray.create("town-names")

        addSyntax( {player, resident, context ->
            // set town home territory
            for (town in context[townsArg]) {
                val terrHome = Nodes.territories.get(town.home)
                if (terrHome !== null) {
                    val spawnpoint = Nodes.getDefaultSpawnLocation(terrHome)
                    town.spawnpoint = spawnpoint
                    town.needsUpdate()
                    Message.print(player, "Set town \"${town.name}\" spawnpoint to $spawnpoint")
                } else {
                    Message.error(player, "Town \"${town.name}\" home territory ${town.home} does not exist")
                }
            }

            // TODO: move this out
            Nodes.needsSave = true
        }, townsArg)
    }
}
