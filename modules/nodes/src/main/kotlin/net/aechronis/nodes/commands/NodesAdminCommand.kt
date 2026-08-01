/**
 * Admin commands to manage world
 * - modify towns, nations
 * - war enable/disable
 *
 *    /nodesadmin command ...
 *    /nda command
 */

package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.commands.arguments.ArgumentNation
import net.aechronis.nodes.commands.arguments.ArgumentResident
import net.aechronis.nodes.commands.arguments.ArgumentResidentArray
import net.aechronis.nodes.commands.arguments.ArgumentSanitizedString
import net.aechronis.nodes.commands.arguments.ArgumentTerritory
import net.aechronis.nodes.commands.arguments.ArgumentTerritoryArray
import net.aechronis.nodes.commands.arguments.ArgumentTown
import net.aechronis.nodes.commands.arguments.ArgumentTownArray
import net.aechronis.nodes.objects.Building
import net.aechronis.nodes.objects.Farm
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Port
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.FlagWar
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.minestom.server.adventure.audience.Audiences
import net.minestom.server.command.builder.arguments.ArgumentBoolean
import net.minestom.server.command.builder.arguments.ArgumentType

class NodesAdminCommand : NodesCommand("nodesadmin", "nodes.admin", "nda") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "[Nodes] Admin commands:")
            Message.print(player, "/nodesadmin war${ChatColor.WHITE}: Enable/disable war")
            Message.print(player, "/nodesadmin town${ChatColor.WHITE}: Manage towns (see \"/nodesadmin town help\")")
            Message.print(player, "/nodesadmin nation${ChatColor.WHITE}: Manage nations (see \"/nodesadmin nation help\")")
            Message.print(player, "/nodesadmin building${ChatColor.WHITE}: Manage buildings (see \"/nodesadmin building help\")")
            Message.print(player, "/nodesadmin save${ChatColor.WHITE}: Force save world")
            Message.print(player, "/nodesadmin load${ChatColor.WHITE}: Force load world")
            Message.print(player, "/nodesadmin runincome${ChatColor.WHITE}: Runs income for all towns")
        }

        addSubcommand(NodesAdminHelpCommand())
        addSubcommand(NodesAdminWarCommand())
        addSubcommand(NodesAdminTownCommand())
        addSubcommand(NodesAdminNationCommand())
        addSubcommand(NodesAdminBuildingCommand())
        addSubcommand(NodesAdminSaveCommand())
        addSubcommand(NodesAdminLoadCommand())
        addSubcommand(NodesAdminRunIncomeCommand())
    }
}

class NodesAdminHelpCommand : NodesCommand("help", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "[Nodes] Admin commands:")
            Message.print(player, "/nodesadmin war${ChatColor.WHITE}: Enable/disable war")
            Message.print(player, "/nodesadmin town${ChatColor.WHITE}: Manage towns (see \"/nodesadmin town help\")")
            Message.print(player, "/nodesadmin nation${ChatColor.WHITE}: Manage nations (see \"/nodesadmin nation help\")")
            Message.print(player, "/nodesadmin building${ChatColor.WHITE}: Manage buildings (see \"/nodesadmin building help\")")
            Message.print(player, "/nodesadmin save${ChatColor.WHITE}: Force save world")
            Message.print(player, "/nodesadmin load${ChatColor.WHITE}: Force load world")
            Message.print(player, "/nodesadmin runincome${ChatColor.WHITE}: Runs income for all towns")
            Message.print(player, "/nodesadmin debug${ChatColor.WHITE}: World object debugger")
        }
    }
}

class NodesAdminWarCommand : NodesCommand("war", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            FlagWar.printInfo(player, true)
            Message.print(player, "Toggle state: \"/nodesadmin war [enable|disable|skirmish]\"")
        }

        addSubcommand(NodesAdminWarEnableCommand())
        addSubcommand(NodesAdminWarDisableCommand())
        addSubcommand(NodesAdminWarSkirmishCommand())
    }
}

class NodesAdminWarEnableCommand : NodesCommand("enable", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin war enable")
        }

        addSyntax({ player, resident, context ->
            FlagWar.enable(canAnnexTerritories = true, canOnlyAttackBorders = false, destructionEnabled = true)
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled")

            // play MENACING wither spawn sound
            Audiences.all().playSound(Sound.sound(Key.key("entity.wither.spawn"), Sound.Source.PLAYER, 1.0f, 1.0f))
        })
    }
}

class NodesAdminWarDisableCommand : NodesCommand("disable", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin war disable")
        }

        addSyntax({ player, resident, context ->
            if (FlagWar.enabled) {
                FlagWar.disable()
                Message.broadcast("${ChatColor.BOLD}Nodes war disabled")
            } else {
                Message.error(player, "Nodes war already disabled")
            }
        })
    }
}

class NodesAdminWarSkirmishCommand : NodesCommand("skirmish", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin war skirmish")
        }

        addSyntax({ player, resident, context ->
            FlagWar.enable(
                canAnnexTerritories = false,
                canOnlyAttackBorders = true,
                destructionEnabled = Nodes.config.allowDestructionDuringSkirmish,
            )
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes border skirmishes enabled")

            // play MENACING wither spawn sound
            Audiences.all().playSound(Sound.sound(Key.key("entity.wither.spawn"), Sound.Source.PLAYER, 1.0f, 1.0f))
        })
    }
}

class NodesAdminTownCommand : NodesCommand("town", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "${ChatColor.BOLD}[Nodes] Admin town management:")
            Message.print(player, "/nodesadmin town create${ChatColor.WHITE}: Create a new town")
            Message.print(player, "/nodesadmin town delete${ChatColor.WHITE}: Delete existing town")
            Message.print(player, "/nodesadmin town rename${ChatColor.WHITE}: Rename a town")
            Message.print(player, "/nodesadmin town addplayer${ChatColor.WHITE}: Add players to town")
            Message.print(player, "/nodesadmin town removeplayer${ChatColor.WHITE}: Remove players from town")
            Message.print(player, "/nodesadmin town addterritory${ChatColor.WHITE}: Add territories to town")
            Message.print(player, "/nodesadmin town removeterritory${ChatColor.WHITE}: Remove territories from town")
            Message.print(player, "/nodesadmin town captureterritory${ChatColor.WHITE}: Add captured territories to town")
            Message.print(player, "/nodesadmin town releaseterritory${ChatColor.WHITE}: Release captured territories")
            Message.print(player, "/nodesadmin town setspawn${ChatColor.WHITE}: Set town's spawn to location")
            Message.print(player, "/nodesadmin town spawn${ChatColor.WHITE}: Go to town's spawn")
            Message.print(player, "/nodesadmin town addofficer${ChatColor.WHITE}: Add officer to town")
            Message.print(player, "/nodesadmin town removeofficer${ChatColor.WHITE}: Remove officer from town")
            Message.print(player, "/nodesadmin town leader${ChatColor.WHITE}: Set town leader to player")
            Message.print(player, "/nodesadmin town removeleader${ChatColor.WHITE}: Remove leader from a town")
            Message.print(player, "/nodesadmin town color${ChatColor.WHITE}: Set the color of a town")
            Message.print(player, "/nodesadmin town open${ChatColor.WHITE}: Toggle town is open to join")
            Message.print(player, "/nodesadmin town income${ChatColor.WHITE}: View a town's income inventory")
            Message.print(player, "/nodesadmin town plot${ChatColor.WHITE}: Manage a town's plots")
            Message.print(player, "Run a command with no args to see usage.")
        }

        addSubcommand(NodesAdminTownCreateCommand())
        addSubcommand(NodesAdminTownDeleteCommand())
        addSubcommand(NodesAdminTownRenameCommand())
        addSubcommand(NodesAdminTownAddPlayerCommand())
        addSubcommand(NodesAdminTownRemovePlayerCommand())
        addSubcommand(NodesAdminTownAddTerritoryCommand())
        addSubcommand(NodesAdminTownRemoveTerritoryCommand())
        addSubcommand(NodesAdminTownCaptureTerritoryCommand())
        addSubcommand(NodesAdminTownReleaseTerritoryCommand())
        addSubcommand(NodesAdminTownSetSpawnCommand())
        addSubcommand(NodesAdminTownSpawnCommand())
        addSubcommand(NodesAdminTownAddOfficerCommand())
        addSubcommand(NodesAdminTownRemoveOfficerCommand())
        addSubcommand(NodesAdminTownLeaderCommand())
        addSubcommand(NodesAdminTownRemoveLeaderCommand())
        addSubcommand(NodesAdminTownColorCommand())
        addSubcommand(NodesAdminTownIncomeCommand())
        addSubcommand(NodesAdminTownSetHomeCommand())
        addSubcommand(NodesAdminTownDefaultTownSpawnsCommand())
        addSubcommand(NodesAdminTownPlotCommand())
    }
}

class NodesAdminTownCreateCommand : NodesCommand("create", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town create <town-name> <territory-ids>")
        }

        val townArg = ArgumentSanitizedString.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax({ player, resident, context ->
            // first territory is new town home
            val town = Town.create(context[townArg], context[territoriesArg][0], null).getOrElse { err ->
                Message.error(player, "Failed to create town: ${err.message}")
                return@addSyntax
            }

            // add the other territories
            for (i in 1 until context[territoriesArg].size) {
                Town.addTerritory(town, context[territoriesArg][i])
            }

            Message.print(player, "Created town \"${context[townArg]}\" with ${context[territoriesArg].size} territories")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownDeleteCommand : NodesCommand("delete", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town delete <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, context ->
            Town.destroy(context[townArg])
            Message.print(player, "Town \"${context[townArg].name}\" has been deleted")
        }, townArg)
    }
}

class NodesAdminTownRenameCommand : NodesCommand("rename", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town rename <town-name> <new-name>")
        }

        val townArg = ArgumentTown.create("town-name")
        val nameArg = ArgumentSanitizedString.create("new-name")

        addSyntax({ player, resident, context ->
            Town.rename(context[townArg], context[nameArg])
            Message.print(player, "${context[townArg].name} has been renamed to \"${context[nameArg]}\"")
        }, townArg, nameArg)
    }
}

class NodesAdminTownAddPlayerCommand : NodesCommand("addplayer", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town addplayer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSyntax({ player, resident, context ->
            for (resident in context[playersArg]) {
                if (Town.addResident(context[townArg], resident)) {
                    Message.print(player, "Added \"${resident.name}\" to town \"${context[townArg].name}\"")
                } else {
                    Message.error(player, "${resident.name} is already a member of a town")
                }
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownRemovePlayerCommand : NodesCommand("removeplayer", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town removeplayer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSyntax({ player, resident, context ->
            for (resident in context[playersArg]) {
                Town.removeResident(context[townArg], resident)
                Message.print(player, "Removed \"${resident.name}\" from town \"${context[townArg].name}\"")
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownAddTerritoryCommand : NodesCommand("addterritory", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town addterritory <town-name> <territory-ids>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax({ player, resident, context ->
            // add territories
            for (terr in context[territoriesArg]) {
                Town.addTerritory(context[townArg], terr)
            }

            Message.print(player, "Added ${context[territoriesArg].size} territories to town \"${context[townArg].name}\"")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownRemoveTerritoryCommand : NodesCommand("removeterritory", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town removeterritory <town-name> <territory-ids>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax({ player, resident, context ->
            // remove territories
            for (terr in context[territoriesArg]) {
                Town.unclaim(context[townArg], terr)
            }

            Message.print(player, "Removed ${context[territoriesArg].size} territories from town \"${context[townArg].name}\"")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownCaptureTerritoryCommand : NodesCommand("captureterritory", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town captureterritory <town-name> <territory-ids>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax({ player, resident, context ->
            // add territories
            for (terr in context[territoriesArg]) {
                Town.capture(context[townArg], terr)
            }

            Message.print(player, "Captured ${context[territoriesArg].size} territories for town \"${context[townArg].name}\"")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownReleaseTerritoryCommand : NodesCommand("releaseterritory", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town releaseterritory <territory-ids>")
        }

        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax({ player, resident, context ->
            // add territories
            for (terr in context[territoriesArg]) {
                Town.release(terr)
            }

            Message.print(player, "Released ${context[territoriesArg].size} territories under occupation")
        }, territoriesArg)
    }
}

class NodesAdminTownAddOfficerCommand : NodesCommand("addofficer", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town addofficer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSyntax({ player, resident, context ->
            // make residents officers
            for (r in context[playersArg]) {
                Town.addOfficer(context[townArg], r)
                Message.print(player, "Made \"${r.name}\" officer of \"${context[townArg].name}\"")
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownRemoveOfficerCommand : NodesCommand("removeofficer", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town removeofficer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSyntax({ player, resident, context ->
            // make residents officers
            for (r in context[playersArg]) {
                Town.removeOfficer(context[townArg], r)
                Message.print(player, "Removed \"${r.name}\" as officer of \"${context[townArg].name}\"")
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownLeaderCommand : NodesCommand("leader", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town leader <town-name> <player-name>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, context ->
            if (context[playerArg].town !== context[townArg]) {
                Message.error(player, "Player \"${context[playerArg].name}\" is not a member of \"${context[townArg].name}\"")
                return@addSyntax
            }

            Town.setLeader(context[townArg], context[playerArg])
            Message.print(player, "Player \"${context[playerArg].name}\" is now leader of \"${context[townArg].name}\"")
        }, townArg, playerArg)
    }
}

class NodesAdminTownRemoveLeaderCommand : NodesCommand("removeleader", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town removeleader <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, context ->
            Town.setLeader(context[townArg], null)
            Message.print(player, "Removed leader of \"${context[townArg].name}\"")
        }, townArg)
    }
}

class NodesAdminTownColorCommand : NodesCommand("color", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town color <town-name> <r> <g> <b>")
        }

        val townArg = ArgumentTown.create("town-name")
        val rArg = ArgumentType.Integer("r")
        val gArg = ArgumentType.Integer("g")
        val bArg = ArgumentType.Integer("b")

        addSyntax({ player, resident, context ->
            Town.setColor(context[townArg], context[rArg], context[gArg], context[bArg])
            Message.print(player, "Set color of ${context[townArg].name} to (${context[rArg]}, ${context[gArg]}, ${context[bArg]})")
        }, townArg, rArg, gArg, bArg)
    }
}

class NodesAdminTownIncomeCommand : NodesCommand("income", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town income <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, context ->
            // open town inventory
            player.openInventory(Town.incomeInventory(context[townArg]))
        }, townArg)
    }
}

class NodesAdminTownSetSpawnCommand : NodesCommand("setspawn", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town setspawn <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, context ->
            val result = Town.setSpawn(context[townArg], player.position)

            if (result) {
                Message.print(player, "Town \"${context[townArg].name}\" spawn set to current location")
            } else {
                Message.error(player, "Spawn location must be within town's home territory")
            }
        }, townArg)
    }
}

class NodesAdminTownSpawnCommand : NodesCommand("spawn", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town spawn <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, context ->
            player.teleport(context[townArg].spawnpoint)
        }, townArg)
    }
}

class NodesAdminTownSetHomeCommand : NodesCommand("sethome", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town sethome <town-name> <territory-id>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoryArg = ArgumentTerritory.create("territory-id")

        addSyntax({ player, resident, context ->
            // set town home territory
            if (context[townArg] !== context[territoryArg].town) {
                Message.error(player, "Invalid territory id=${context[territoryArg].id}: does not belong to town")
                return@addSyntax
            }

            if (context[townArg].home == context[territoryArg].id) {
                Message.error(player, "Invalid territory id=${context[territoryArg].id}: already is home territory")
                return@addSyntax
            }

            Town.setHome(context[townArg], context[territoryArg])
            Message.print(player, "Moved \"${context[townArg].name}\" home territory to id = ${context[territoryArg].id}")
        }, townArg, territoryArg)
    }
}

class NodesAdminTownDefaultTownSpawnsCommand : NodesCommand("defaulttownspawns", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town defaulttownspawns <town-names>")
        }

        val townsArg = ArgumentTownArray.create("town-names")

        addSyntax({ player, resident, context ->
            // set town home territory
            for (town in context[townsArg]) {
                val terrHome = Nodes.territories.get(town.home)
                if (terrHome !== null) {
                    val spawnpoint = Territory.defaultSpawnLocation(terrHome)
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

class NodesAdminNationCommand : NodesCommand("nation", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "${ChatColor.BOLD}[Nodes] Admin nation management:")
            Message.print(player, "/nodesadmin nation create${ChatColor.WHITE}: Create a new nation")
            Message.print(player, "/nodesadmin nation delete${ChatColor.WHITE}: Delete existing nation")
            Message.print(player, "/nodesadmin nation rename${ChatColor.WHITE}: Rename a nation")
            Message.print(player, "/nodesadmin nation addtown${ChatColor.WHITE}: Add towns to nation")
            Message.print(player, "/nodesadmin nation removetown${ChatColor.WHITE}: Remove towns from nation")
            Message.print(player, "/nodesadmin nation addally${ChatColor.WHITE}: Add ally to nation")
            Message.print(player, "/nodesadmin nation removeally${ChatColor.WHITE}: Remove ally from a nation")
            Message.print(player, "/nodesadmin nation addenemy${ChatColor.WHITE}: Add enemy to nation")
            Message.print(player, "/nodesadmin nation removeenemy${ChatColor.WHITE}: Remove enemy from a nation")
            Message.print(player, "/nodesadmin nation capital${ChatColor.WHITE}: Set nation's capital town")
            Message.print(player, "/nodesadmin nation color${ChatColor.WHITE}: Set the color of a nation")
            Message.print(player, "Run a command with no args to see usage.")
        }

        addSubcommand(NodesAdminNationCreateCommand())
        addSubcommand(NodesAdminNationDeleteCommand())
        addSubcommand(NodesAdminNationRenameCommand())
        addSubcommand(NodesAdminNationAddTownCommand())
        addSubcommand(NodesAdminNationRemoveTownCommand())
        addSubcommand(NodesAdminNationAddAllyCommand())
        addSubcommand(NodesAdminNationRemoveAllyCommand())
        addSubcommand(NodesAdminNationAddEnemyCommand())
        addSubcommand(NodesAdminNationRemoveEnemyCommand())
        addSubcommand(NodesAdminNationCapitalCommand())
        addSubcommand(NodesAdminNationColorCommand())
    }
}

class NodesAdminNationCreateCommand : NodesCommand("create", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation create <nation-name> <town-names>")
        }

        val nationArg = ArgumentSanitizedString.create("nation-name")
        val townsArg = ArgumentTownArray.create("town-names")

        addSyntax({ player, resident, context ->
            // create new nation from town
            val nation = Nation.create(context[nationArg], context[townsArg][0], context[townsArg][0].leader).getOrElse { err ->
                Message.error(player, "Failed to create nation: ${err.message}")
                return@addSyntax
            }

            // add other towns
            for (i in 1 until context[townsArg].size) {
                Nation.addTown(nation, context[townsArg][i])
            }

            Message.print(player, "Created nation \"${context[nationArg]}\" with ${context[townsArg].size} towns")
        }, nationArg, townsArg)
    }
}

class NodesAdminNationDeleteCommand : NodesCommand("delete", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation delete <nation-name>")
        }

        val nationArg = ArgumentNation.create("nation-name")

        addSyntax({ player, resident, context ->
            Nation.destroy(context[nationArg])
            Message.print(player, "Nation \"${context[nationArg].name}\" has been deleted")
        }, nationArg)
    }
}

class NodesAdminNationRenameCommand : NodesCommand("rename", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation rename <nation-name> <new-name>")
        }

        val nationArg = ArgumentNation.create("nation-name")
        val nameArg = ArgumentSanitizedString.create("new-name")

        addSyntax({ player, resident, context ->
            Nation.rename(context[nationArg], context[nameArg])
            Message.print(player, "${context[nationArg].name} has been renamed to \"${context[nameArg]}\"")
        }, nationArg, nameArg)
    }
}

class NodesAdminNationAddTownCommand : NodesCommand("addtown", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation addtown <nation-name> <town-names>")
        }

        val nationArg = ArgumentNation.create("nation-name")
        val townsArg = ArgumentTownArray.create("town-names")

        addSyntax({ player, resident, context ->
            // Validate all towns first
            for (town in context[townsArg]) {
                if (town.nation != null) {
                    Message.error(player, "Town \"${town.name}\" already has a nation")
                    return@addSyntax
                }
            }

            // Process all towns if validation passed
            for (town in context[townsArg]) {
                Nation.addTown(context[nationArg], town)
                Message.print(player, "Added town \"${town.name}\" to nation \"${context[nationArg].name}\"")
            }
        }, nationArg, townsArg)
    }
}

class NodesAdminNationRemoveTownCommand : NodesCommand("removetown", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation removetown <nation-name> <town-names>")
        }

        val nationArg = ArgumentNation.create("nation-name")
        val townsArg = ArgumentTownArray.create("town-names")

        addSyntax({ player, resident, context ->
            // Validate all towns first
            for (town in context[townsArg]) {
                if (town.nation != context[nationArg]) {
                    Message.error(player, "Town \"${town.name}\" does not belong to nation \"${context[nationArg].name}\"")
                    return@addSyntax
                }
            }

            // Process all towns if validation passed
            for (town in context[townsArg]) {
                Nation.removeTown(context[nationArg], town)
                Message.print(player, "Removed town \"${town.name}\" from nation \"${context[nationArg].name}\"")
            }
        }, nationArg, townsArg)
    }
}

class NodesAdminNationCapitalCommand : NodesCommand("capital", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation capital <nation-name> <town-name>")
        }

        val nationArg = ArgumentNation.create("nation-name")
        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, context ->
            if (context[townArg].nation !== context[nationArg]) {
                Message.error(player, "Town does not belong to this nation")
                return@addSyntax
            }
            if (context[townArg] === context[nationArg].capital) {
                Message.error(player, "Town is already the nation capital")
                return@addSyntax
            }

            Nation.setCapital(context[nationArg], context[townArg])

            Message.print(player, "${context[townArg].name} is now the capital of ${context[nationArg].name}")
        }, nationArg, townArg)
    }
}

class NodesAdminNationAddAllyCommand : NodesCommand("addally", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation addally <nationA-name> <nationB-name>")
        }

        val nationAArg = ArgumentNation.create("nationA-name")
        val nationBArg = ArgumentNation.create("nationB-name")

        addSyntax({ player, resident, context ->
            Nation.addAlly(context[nationAArg], context[nationBArg]).getOrElse { err ->
                Message.error(player, "Failed to add ally: ${err.message}")
                return@addSyntax
            }

            Message.print(player, "Added ${context[nationBArg].name} as ally of ${context[nationAArg].name}")
        }, nationAArg, nationBArg)
    }
}

class NodesAdminNationRemoveAllyCommand : NodesCommand("removeally", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation removeally <nationA-name> <nationB-name>")
        }

        val nationAArg = ArgumentNation.create("nationA-name")
        val nationBArg = ArgumentNation.create("nationB-name")

        addSyntax({ player, resident, context ->
            Nation.removeAlly(context[nationAArg], context[nationBArg]).getOrElse { err ->
                Message.error(player, "Failed to remove ally: ${err.message}")
                return@addSyntax
            }

            Message.print(player, "Removed ${context[nationBArg].name} as ally of ${context[nationAArg].name}")
        }, nationAArg, nationBArg)
    }
}

class NodesAdminNationAddEnemyCommand : NodesCommand("addenemy", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation addenemy <nationA-name> <nationB-name>")
        }

        val nationAArg = ArgumentNation.create("nationA-name")
        val nationBArg = ArgumentNation.create("nationB-name")

        addSyntax({ player, resident, context ->
            Nation.addEnemy(context[nationAArg], context[nationBArg]).getOrElse { err ->
                Message.error(player, "Failed to add enemy: ${err.message}")
                return@addSyntax
            }

            Message.print(player, "Added ${context[nationBArg].name} as enemy of ${context[nationAArg].name}")
        }, nationAArg, nationBArg)
    }
}

class NodesAdminNationRemoveEnemyCommand : NodesCommand("removeenemy", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation removeenemy <nationA-name> <nationB-name>")
        }

        val nationAArg = ArgumentNation.create("nationA-name")
        val nationBArg = ArgumentNation.create("nationB-name")

        addSyntax({ player, resident, context ->
            Nation.removeEnemy(context[nationAArg], context[nationBArg]).getOrElse { err ->
                Message.error(player, "Failed to remove enemy: ${err.message}")
                return@addSyntax
            }

            Message.print(player, "Removed ${context[nationBArg].name} as enemy of ${context[nationAArg].name}")
        }, nationAArg, nationBArg)
    }
}

class NodesAdminNationColorCommand : NodesCommand("color", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation color <nation-name> <r> <g> <b>")
        }

        val nationArg = ArgumentNation.create("nation-name")
        val rArg = ArgumentType.Integer("r")
        val gArg = ArgumentType.Integer("g")
        val bArg = ArgumentType.Integer("b")

        addSyntax({ player, resident, context ->
            Nation.setColor(context[nationArg], context[rArg], context[gArg], context[bArg])
            Message.print(player, "Set color of ${context[nationArg].name} to (${context[rArg]}, ${context[gArg]}, ${context[bArg]})")
        }, nationArg, rArg, gArg, bArg)
    }
}

class NodesAdminBuildingCommand : NodesCommand("building", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "${ChatColor.AQUA}/nodesadmin building create${ChatColor.WHITE}: Create a new building")
            Message.print(player, "${ChatColor.AQUA}/nodesadmin building delete${ChatColor.WHITE}: Delete the building in your current chunk")
            Message.print(player, "${ChatColor.AQUA}/nodesadmin building settier${ChatColor.WHITE}: Set tier of the building in your current chunk")
            Message.print(player, "Run a command with no args to see usage.")
        }

        addSubcommand(NodesAdminBuildingCreateCommand())
        addSubcommand(NodesAdminBuildingDeleteCommand())
        addSubcommand(NodesAdminBuildingSetTierCommand())
    }
}

class NodesAdminBuildingCreateCommand : NodesCommand("create", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage:")
            Message.print(player, "/nodesadmin building create port <name> <public> [tier]")
            Message.print(player, "/nodesadmin building create farm [tier]")
        }

        val portLit = ArgumentType.Literal("port")
        val farmLit = ArgumentType.Literal("farm")
        val nameArg = ArgumentSanitizedString.create("name")
        val publicArg = ArgumentBoolean("public")
        val tierArg = ArgumentType.Integer("tier").between(1, 3)

        addSyntax({ player, resident, context ->
            Port.create(
                name,
                Math.floorDiv(player.position.blockX(), 16),
                Math.floorDiv(player.position.blockZ(), 16),
                context[tierArg],
                context[publicArg],
            ).getOrElse { err ->
                Message.error(player, "Failed to create port: ${err.message}")
                return@addSyntax
            }
            Message.print(player, "Created port \"$name\" (tier ${context[tierArg]})")
        }, portLit, nameArg, publicArg, tierArg)

        addSyntax({ player, resident, context ->
            Farm.create(
                Math.floorDiv(player.position.blockX(), 16),
                Math.floorDiv(player.position.blockZ(), 16),
                context[tierArg],
            ).getOrElse { err ->
                Message.error(player, "Failed to create farm: ${err.message}")
                return@addSyntax
            }
            Message.print(player, "Created farm (tier $context[tierArg])")
        }, farmLit, tierArg)
    }
}

private fun buildingAtPlayer(player: net.minestom.server.entity.Player): net.aechronis.nodes.objects.Building? {
    val chunkX = Math.floorDiv(player.position.blockX(), 16)
    val chunkZ = Math.floorDiv(player.position.blockZ(), 16)
    return Building.getAt(chunkX, chunkZ)
}

class NodesAdminBuildingDeleteCommand : NodesCommand("delete", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            val building = buildingAtPlayer(player)
            if (building === null) {
                Message.error(player, "No building in this chunk")
                return@setDefaultExecutor
            }
            Building.destroy(building)
            Message.print(player, "Deleted ${building.type} in chunk (${building.chunkX}, ${building.chunkZ})")
        }
    }
}

class NodesAdminBuildingSetTierCommand : NodesCommand("settier", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin building settier <tier>")
        }

        val tierArg = ArgumentType.Integer("tier").between(1, 3)

        addSyntax({ player, resident, context ->
            val building = buildingAtPlayer(player)
            if (building === null) {
                Message.error(player, "No building in this chunk")
                return@addSyntax
            }
            Building.setTier(building, context[tierArg])
            Message.print(player, "${building.type} in chunk (${building.chunkX}, ${building.chunkZ}) set to tier ${building.tier}")
        }, tierArg)
    }
}

class NodesAdminSaveCommand : NodesCommand("save", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage:")
            Message.print(player, "/nodesadmin save")
            Message.print(player, "/nodesadmin save <sync>")
        }

        val syncArg = ArgumentType.Boolean("sync")

        addSyntax({ player, resident, context ->
            Message.print(player, "[Nodes] Saving world (async)")
            Nodes.saveWorld(checkIfNeedsSave = false, async = true)
        })

        addSyntax({ player, resident, context ->
            if (context[syncArg]) {
                Message.print(player, "[Nodes] Saving world (sync)")
                Nodes.saveWorld(checkIfNeedsSave = false, async = false)
            } else {
                Message.print(player, "[Nodes] Saving world (async)")
                Nodes.saveWorld(checkIfNeedsSave = false, async = true)
            }
        }, syncArg)
    }
}

class NodesAdminLoadCommand : NodesCommand("load", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin load")
        }

        addSyntax({ player, resident, context ->
            Message.print(player, "[Nodes] Loading world")
            Nodes.loadWorld()
        })
    }
}

class NodesAdminRunIncomeCommand : NodesCommand("runincome", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin runincome")
        }

        addSyntax({ player, resident, context ->
            Message.print(player, "Running incomes for all towns")
            Nodes.runIncome()
        })
    }
}
