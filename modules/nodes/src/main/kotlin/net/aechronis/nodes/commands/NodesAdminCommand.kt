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
import net.aechronis.nodes.commands.arguments.ArgumentPort
import net.aechronis.nodes.commands.arguments.ArgumentPortArray
import net.aechronis.nodes.commands.arguments.ArgumentPortGroup
import net.aechronis.nodes.commands.arguments.ArgumentResident
import net.aechronis.nodes.commands.arguments.ArgumentResidentArray
import net.aechronis.nodes.commands.arguments.ArgumentSanitizedString
import net.aechronis.nodes.commands.arguments.ArgumentTerritory
import net.aechronis.nodes.commands.arguments.ArgumentTerritoryArray
import net.aechronis.nodes.commands.arguments.ArgumentTown
import net.aechronis.nodes.commands.arguments.ArgumentTownArray
import net.aechronis.nodes.objects.Command
import net.aechronis.nodes.utils.ChatColor
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.minestom.server.adventure.audience.Audiences
import net.minestom.server.command.builder.arguments.ArgumentBoolean
import net.minestom.server.command.builder.arguments.ArgumentType

class NodesAdminCommand : Command("nodesadmin", "nodes.admin", "nda") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "[Nodes] Admin commands:")
            Message.print(player, "/nodesadmin war${ChatColor.WHITE}: Enable/disable war")
            Message.print(player, "/nodesadmin town${ChatColor.WHITE}: Manage towns (see \"/nodesadmin town help\")")
            Message.print(player, "/nodesadmin nation${ChatColor.WHITE}: Manage nations (see \"/nodesadmin nation help\")")
            Message.print(player, "/nodesadmin port${ChatColor.WHITE}: Manage ports (see \"/nodesadmin port help\")")
            Message.print(player, "/nodesadmin portgroup${ChatColor.WHITE}: Manage port groups (see \"/nodesadmin portgroup help\")")
            Message.print(player, "/nodesadmin save${ChatColor.WHITE}: Force save world")
            Message.print(player, "/nodesadmin load${ChatColor.WHITE}: Force load world")
            Message.print(player, "/nodesadmin runincome${ChatColor.WHITE}: Runs income for all towns")
        }

        addSubcommand(NodesAdminHelpCommand())
        addSubcommand(NodesAdminWarCommand())
        addSubcommand(NodesAdminTownCommand())
        addSubcommand(NodesAdminNationCommand())
        addSubcommand(NodesAdminPortCommand())
        addSubcommand(NodesAdminPortGroupCommand())
        addSubcommand(NodesAdminSaveCommand())
        addSubcommand(NodesAdminLoadCommand())
        addSubcommand(NodesAdminRunIncomeCommand())
    }
}

class NodesAdminHelpCommand : Command("help", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "[Nodes] Admin commands:")
            Message.print(player, "/nodesadmin war${ChatColor.WHITE}: Enable/disable war")
            Message.print(player, "/nodesadmin town${ChatColor.WHITE}: Manage towns (see \"/nodesadmin town help\")")
            Message.print(player, "/nodesadmin nation${ChatColor.WHITE}: Manage nations (see \"/nodesadmin nation help\")")
            Message.print(player, "/nodesadmin port${ChatColor.WHITE}: Manage ports (see \"/nodesadmin port help\")")
            Message.print(player, "/nodesadmin portgroup${ChatColor.WHITE}: Manage port groups (see \"/nodesadmin portgroup help\")")
            Message.print(player, "/nodesadmin save${ChatColor.WHITE}: Force save world")
            Message.print(player, "/nodesadmin load${ChatColor.WHITE}: Force load world")
            Message.print(player, "/nodesadmin runincome${ChatColor.WHITE}: Runs income for all towns")
            Message.print(player, "/nodesadmin debug${ChatColor.WHITE}: World object debugger")
        }
    }
}

class NodesAdminWarCommand : Command("war", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Nodes.war.printInfo(player, true)
            Message.print(player, "Toggle state: \"/nodesadmin war [enable|disable|skirmish]\"")
        }

        addSubcommand(NodesAdminWarEnableCommand())
        addSubcommand(NodesAdminWarDisableCommand())
        addSubcommand(NodesAdminWarSkirmishCommand())
    }
}

class NodesAdminWarEnableCommand : Command("enable", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin war enable")
        }

        addSyntax({ player, resident, context ->
            Nodes.enableWar(canAnnexTerritories = true, canOnlyAttackBorders = false, destructionEnabled = true)
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled")

            // play MENACING wither spawn sound
            Audiences.all().playSound(Sound.sound(Key.key("entity.wither.spawn"), Sound.Source.PLAYER, 1.0f, 1.0f))
        })
    }
}

class NodesAdminWarDisableCommand : Command("disable", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin war disable")
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

class NodesAdminWarSkirmishCommand : Command("skirmish", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin war skirmish")
        }

        addSyntax({ player, resident, context ->
            Nodes.enableWar(
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

class NodesAdminTownCommand : Command("town", "nodes.admin") {
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
    }
}

class NodesAdminTownCreateCommand : Command("create", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town create <town-name> <territory-ids>")
        }

        val townArg = ArgumentSanitizedString.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax({ player, resident, context ->
            // first territory is new town home
            val town = Nodes.createTown(context[townArg], context[territoriesArg][0], null).getOrElse { err ->
                Message.error(player, "Failed to create town: ${err.message}")
                return@addSyntax
            }

            // add the other territories
            for (i in 1 until context[territoriesArg].size) {
                Nodes.addTerritoryToTown(town, context[territoriesArg][i])
            }

            Message.print(player, "Created town \"${context[townArg]}\" with ${context[territoriesArg].size} territories")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownDeleteCommand : Command("delete", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town delete <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, context ->
            Nodes.destroyTown(context[townArg])
            Message.print(player, "Town \"${context[townArg].name}\" has been deleted")
        }, townArg)
    }
}

class NodesAdminTownRenameCommand : Command("rename", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town rename <town-name> <new-name>")
        }

        val townArg = ArgumentTown.create("town-name")
        val nameArg = ArgumentSanitizedString.create("new-name")

        addSyntax({ player, resident, context ->
            Nodes.renameTown(context[townArg], context[nameArg])
            Message.print(player, "${context[townArg].name} has been renamed to \"${context[nameArg]}\"")
        }, townArg, nameArg)
    }
}

class NodesAdminTownAddPlayerCommand : Command("addplayer", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town addplayer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSyntax({ player, resident, context ->
            for (resident in context[playersArg]) {
                Nodes.addResidentToTown(context[townArg], resident)
                Message.print(player, "Added \"${resident.name}\" to town \"${context[townArg].name}\"")
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownRemovePlayerCommand : Command("removeplayer", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town removeplayer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSyntax({ player, resident, context ->
            for (resident in context[playersArg]) {
                Nodes.removeResidentFromTown(context[townArg], resident)
                Message.print(player, "Removed \"${resident.name}\" from town \"${context[townArg].name}\"")
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownAddTerritoryCommand : Command("addterritory", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town addterritory <town-name> <territory-ids>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax({ player, resident, context ->
            // add territories
            for (terr in context[territoriesArg]) {
                Nodes.addTerritoryToTown(context[townArg], terr)
            }

            Message.print(player, "Added ${context[territoriesArg].size} territories to town \"${context[townArg].name}\"")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownRemoveTerritoryCommand : Command("removeterritory", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town removeterritory <town-name> <territory-ids>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax({ player, resident, context ->
            // remove territories
            for (terr in context[territoriesArg]) {
                Nodes.unclaimTerritory(context[townArg], terr)
            }

            Message.print(player, "Removed ${context[territoriesArg].size} territories from town \"${context[townArg].name}\"")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownCaptureTerritoryCommand : Command("captureterritory", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town captureterritory <town-name> <territory-ids>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax({ player, resident, context ->
            // add territories
            for (terr in context[territoriesArg]) {
                Nodes.captureTerritory(context[townArg], terr)
            }

            Message.print(player, "Captured ${context[territoriesArg].size} territories for town \"${context[townArg].name}\"")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownReleaseTerritoryCommand : Command("releaseterritory", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town releaseterritory <territory-ids>")
        }

        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSyntax({ player, resident, context ->
            // add territories
            for (terr in context[territoriesArg]) {
                Nodes.releaseTerritory(terr)
            }

            Message.print(player, "Released ${context[territoriesArg].size} territories under occupation")
        }, territoriesArg)
    }
}

class NodesAdminTownAddOfficerCommand : Command("addofficer", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town addofficer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSyntax({ player, resident, context ->
            // make residents officers
            for (r in context[playersArg]) {
                Nodes.townAddOfficer(context[townArg], r)
                Message.print(player, "Made \"${r.name}\" officer of \"${context[townArg].name}\"")
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownRemoveOfficerCommand : Command("removeofficer", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town removeofficer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSyntax({ player, resident, context ->
            // make residents officers
            for (r in context[playersArg]) {
                Nodes.townRemoveOfficer(context[townArg], r)
                Message.print(player, "Removed \"${r.name}\" as officer of \"${context[townArg].name}\"")
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownLeaderCommand : Command("leader", "nodes.admin") {
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

            Nodes.townSetLeader(context[townArg], context[playerArg])
            Message.print(player, "Player \"${context[playerArg].name}\" is now leader of \"${context[townArg].name}\"")
        }, townArg, playerArg)
    }
}

class NodesAdminTownRemoveLeaderCommand : Command("removeleader", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town removeleader <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, context ->
            Nodes.townSetLeader(context[townArg], null)
            Message.print(player, "Removed leader of \"${context[townArg].name}\"")
        }, townArg)
    }
}

class NodesAdminTownColorCommand : Command("color", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town color <town-name> <r> <g> <b>")
        }

        val townArg = ArgumentTown.create("town-name")
        val rArg = ArgumentType.Integer("r")
        val gArg = ArgumentType.Integer("g")
        val bArg = ArgumentType.Integer("b")

        addSyntax({ player, resident, context ->
            Nodes.setTownColor(context[townArg], context[rArg], context[gArg], context[bArg])
            Message.print(player, "Set color of ${context[townArg].name} to (${context[rArg]}, ${context[gArg]}, ${context[bArg]})")
        }, townArg, rArg, gArg, bArg)
    }
}

class NodesAdminTownIncomeCommand : Command("income", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town income <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, context ->
            // open town inventory
            player.openInventory(Nodes.getTownIncomeInventory(context[townArg]))
        }, townArg)
    }
}

class NodesAdminTownSetSpawnCommand : Command("setspawn", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin town setspawn <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, context ->
            val result = Nodes.setTownSpawn(context[townArg], player.position)

            if (result) {
                Message.print(player, "Town \"${context[townArg].name}\" spawn set to current location")
            } else {
                Message.error(player, "Spawn location must be within town's home territory")
            }
        }, townArg)
    }
}

class NodesAdminTownSpawnCommand : Command("spawn", "nodes.admin") {
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

class NodesAdminTownSetHomeCommand : Command("sethome", "nodes.admin") {
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

            Nodes.setTownHomeTerritory(context[townArg], context[territoryArg])
            Message.print(player, "Moved \"${context[townArg].name}\" home territory to id = ${context[territoryArg].id}")
        }, townArg, territoryArg)
    }
}

class NodesAdminTownDefaultTownSpawnsCommand : Command("defaulttownspawns", "nodes.admin") {
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

class NodesAdminNationCommand : Command("nation", "nodes.admin") {
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

class NodesAdminNationCreateCommand : Command("create", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation create <nation-name> <town-names>")
        }

        val nationArg = ArgumentSanitizedString.create("nation-name")
        val townsArg = ArgumentTownArray.create("town-names")

        addSyntax({ player, resident, context ->
            // create new nation from town
            val nation = Nodes.createNation(context[nationArg], context[townsArg][0], context[townsArg][0].leader).getOrElse { err ->
                Message.error(player, "Failed to create nation: ${err.message}")
                return@addSyntax
            }

            // add other towns
            for (i in 1 until context[townsArg].size) {
                Nodes.addTownToNation(nation, context[townsArg][i])
            }

            Message.print(player, "Created nation \"${context[nationArg]}\" with ${context[townsArg].size} towns")
        }, nationArg, townsArg)
    }
}

class NodesAdminNationDeleteCommand : Command("delete", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation delete <nation-name>")
        }

        val nationArg = ArgumentNation.create("nation-name")

        addSyntax({ player, resident, context ->
            Nodes.destroyNation(context[nationArg])
            Message.print(player, "Nation \"${context[nationArg].name}\" has been deleted")
        }, nationArg)
    }
}

class NodesAdminNationRenameCommand : Command("rename", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation rename <nation-name> <new-name>")
        }

        val nationArg = ArgumentNation.create("nation-name")
        val nameArg = ArgumentSanitizedString.create("new-name")

        addSyntax({ player, resident, context ->
            Nodes.renameNation(context[nationArg], context[nameArg])
            Message.print(player, "${context[nationArg].name} has been renamed to \"${context[nameArg]}\"")
        }, nationArg, nameArg)
    }
}

class NodesAdminNationAddTownCommand : Command("addtown", "nodes.admin") {
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
                Nodes.addTownToNation(context[nationArg], town)
                Message.print(player, "Added town \"${town.name}\" to nation \"${context[nationArg].name}\"")
            }
        }, nationArg, townsArg)
    }
}

class NodesAdminNationRemoveTownCommand : Command("removetown", "nodes.admin") {
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
                Nodes.removeTownFromNation(context[nationArg], town)
                Message.print(player, "Removed town \"${town.name}\" from nation \"${context[nationArg].name}\"")
            }
        }, nationArg, townsArg)
    }
}

class NodesAdminNationCapitalCommand : Command("capital", "nodes.admin") {
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

            Nodes.setNationCapital(context[nationArg], context[townArg])

            Message.print(player, "${context[townArg].name} is now the capital of ${context[nationArg].name}")
        }, nationArg, townArg)
    }
}

class NodesAdminNationAddAllyCommand : Command("addally", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation addally <nationA-name> <nationB-name>")
        }

        val nationAArg = ArgumentNation.create("nationA-name")
        val nationBArg = ArgumentNation.create("nationB-name")

        addSyntax({ player, resident, context ->
            Nodes.addAlly(context[nationAArg], context[nationBArg]).getOrElse { err ->
                Message.error(player, "Failed to add ally: ${err.message}")
                return@addSyntax
            }

            Message.print(player, "Added ${context[nationBArg].name} as ally of ${context[nationAArg].name}")
        }, nationAArg, nationBArg)
    }
}

class NodesAdminNationRemoveAllyCommand : Command("removeally", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation removeally <nationA-name> <nationB-name>")
        }

        val nationAArg = ArgumentNation.create("nationA-name")
        val nationBArg = ArgumentNation.create("nationB-name")

        addSyntax({ player, resident, context ->
            Nodes.removeAlly(context[nationAArg], context[nationBArg]).getOrElse { err ->
                Message.error(player, "Failed to remove ally: ${err.message}")
                return@addSyntax
            }

            Message.print(player, "Removed ${context[nationBArg].name} as ally of ${context[nationAArg].name}")
        }, nationAArg, nationBArg)
    }
}

class NodesAdminNationAddEnemyCommand : Command("addenemy", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation addenemy <nationA-name> <nationB-name>")
        }

        val nationAArg = ArgumentNation.create("nationA-name")
        val nationBArg = ArgumentNation.create("nationB-name")

        addSyntax({ player, resident, context ->
            Nodes.addEnemy(context[nationAArg], context[nationBArg]).getOrElse { err ->
                Message.error(player, "Failed to add enemy: ${err.message}")
                return@addSyntax
            }

            Message.print(player, "Added ${context[nationBArg].name} as enemy of ${context[nationAArg].name}")
        }, nationAArg, nationBArg)
    }
}

class NodesAdminNationRemoveEnemyCommand : Command("removeenemy", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation removeenemy <nationA-name> <nationB-name>")
        }

        val nationAArg = ArgumentNation.create("nationA-name")
        val nationBArg = ArgumentNation.create("nationB-name")

        addSyntax({ player, resident, context ->
            Nodes.removeEnemy(context[nationAArg], context[nationBArg]).getOrElse { err ->
                Message.error(player, "Failed to remove enemy: ${err.message}")
                return@addSyntax
            }

            Message.print(player, "Removed ${context[nationBArg].name} as enemy of ${context[nationAArg].name}")
        }, nationAArg, nationBArg)
    }
}

class NodesAdminNationColorCommand : Command("color", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin nation color <nation-name> <r> <g> <b>")
        }

        val nationArg = ArgumentNation.create("nation-name")
        val rArg = ArgumentType.Integer("r")
        val gArg = ArgumentType.Integer("g")
        val bArg = ArgumentType.Integer("b")

        addSyntax({ player, resident, context ->
            Nodes.setNationColor(context[nationArg], context[rArg], context[gArg], context[bArg])
            Message.print(player, "Set color of ${context[nationArg].name} to (${context[rArg]}, ${context[gArg]}, ${context[bArg]})")
        }, nationArg, rArg, gArg, bArg)
    }
}

class NodesAdminPortCommand : Command("port", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "${ChatColor.AQUA}/nodesadmin port create${ChatColor.WHITE}: Create a new port")
            Message.print(player, "${ChatColor.AQUA}/nodesadmin port delete${ChatColor.WHITE}: Delete a port")
            Message.print(player, "Run a command with no args to see usage.")
        }

        addSubcommand(NodesAdminPortCreateCommand())
        addSubcommand(NodesAdminPortDeleteCommand())
    }
}

class NodesAdminPortCreateCommand : Command("create", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin port create <port-name> <public>")
        }

        val portArg = ArgumentSanitizedString.create("port-name")
        val publicArg = ArgumentBoolean("public")

        addSyntax({ player, resident, context ->
            Nodes.createPort(
                context[portArg],
                player.position.blockX(),
                player.position.blockZ(),
                hashSetOf(),
                context[publicArg],
            ).getOrElse { err ->
                Message.error(player, "Failed to create port: ${err.message}")
                return@addSyntax
            }

            Message.print(player, "Created port \"${context[portArg]}\"")
        }, portArg, publicArg)
    }
}

class NodesAdminPortDeleteCommand : Command("delete", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin port delete <port-name>")
        }

        val portArg = ArgumentPort.create("port-name")

        addSyntax({ player, resident, context ->
            // delete the port
            Nodes.destroyPort(context[portArg])

            Message.print(player, "Port \"${context[portArg].name}\" has been deleted")
        }, portArg)
    }
}

class NodesAdminPortGroupCommand : Command("portgroup", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "${ChatColor.AQUA}/nodesadmin portgroup create${ChatColor.WHITE}: Create a new port group")
            Message.print(player, "${ChatColor.AQUA}/nodesadmin portgroup delete${ChatColor.WHITE}: Delete a port group")
            Message.print(player, "${ChatColor.AQUA}/nodesadmin portgroup addport${ChatColor.WHITE}: Add a port to a group")
            Message.print(player, "${ChatColor.AQUA}/nodesadmin portgroup removeport${ChatColor.WHITE}: Remove a port from a group")
            Message.print(player, "Run a command with no args to see usage.")
        }

        addSubcommand(NodesAdminPortGroupCreateCommand())
        addSubcommand(NodesAdminPortGroupDeleteCommand())
        addSubcommand(NodesAdminPortGroupAddPortCommand())
        addSubcommand(NodesAdminPortGroupRemovePortCommand())
    }
}

class NodesAdminPortGroupCreateCommand : Command("create", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage:")
            Message.print(player, "/nodesadmin portgroup create <port-group-name>")
            Message.print(player, "/nodesadmin portgroup create <port-group-name> <port-names>")
        }

        val portGroupArg = ArgumentSanitizedString.create("port-group-name")
        val portsArg = ArgumentPortArray.create("port-names")

        addSyntax({ player, resident, context ->
            Nodes.createPortGroup(
                context[portGroupArg],
            ).getOrElse { err ->
                Message.error(player, "Failed to create group: ${err.message}")
                return@addSyntax
            }

            Message.print(player, "Created group \"${context[portGroupArg]}\"")
        }, portGroupArg)

        addSyntax({ player, resident, context ->
            val portGroup = Nodes.createPortGroup(
                context[portGroupArg],
            ).getOrElse { err ->
                Message.error(player, "Failed to create group: ${err.message}")
                return@addSyntax
            }

            for (port in context[portsArg]) {
                Nodes.addPortToGroup(port, portGroup).getOrElse { err ->
                    Message.error(player, "Failed to add port \"${port.name}\": ${err.message}")
                }
            }

            Message.print(player, "Created group \"${context[portGroupArg]}\" with ${context[portsArg].size} ports")
        }, portGroupArg, portsArg)
    }
}

class NodesAdminPortGroupDeleteCommand : Command("delete", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin portgroup delete <port-group-name>")
        }

        val portGroupArg = ArgumentPortGroup.create("port-group-name")

        addSyntax({ player, resident, context ->
            Nodes.destroyPortGroup(context[portGroupArg])

            Message.print(player, "Port group \"${context[portGroupArg].name}\" has been deleted")
        }, portGroupArg)
    }
}

class NodesAdminPortGroupAddPortCommand : Command("addport", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin portgroup addport <port-group-name> <port-names>")
        }

        val portGroupArg = ArgumentPortGroup.create("port-group-name")
        val portsArg = ArgumentPortArray.create("port-names")

        addSyntax({ player, resident, context ->
            for (port in context[portsArg]) {
                Nodes.addPortToGroup(port, context[portGroupArg]).getOrElse { err ->
                    Message.error(player, "Failed to add port \"${port.name}\": ${err.message}")
                    return@addSyntax
                }
                Message.print(player, "Added port \"${port.name}\" to group \"${context[portGroupArg].name}\"")
            }
        }, portGroupArg, portsArg)
    }
}

class NodesAdminPortGroupRemovePortCommand : Command("removeport", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin portgroup removeport <port-group-name> <port-names>")
        }

        val portGroupArg = ArgumentPortGroup.create("port-group-name")
        val portsArg = ArgumentPortArray.create("port-names")

        addSyntax({ player, resident, context ->
            for (port in context[portsArg]) {
                Nodes.removePortFromGroup(port, context[portGroupArg])
                Message.print(player, "Removed port \"${port.name}\" from group \"${context[portGroupArg].name}\"")
            }
        }, portGroupArg, portsArg)
    }
}

class NodesAdminSaveCommand : Command("save", "nodes.admin") {
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

class NodesAdminLoadCommand : Command("load", "nodes.admin") {
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

class NodesAdminRunIncomeCommand : Command("runincome", "nodes.admin") {
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
