package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.colonization.AiTownConfig
import net.aechronis.nodes.commands.arguments.ArgumentResident
import net.aechronis.nodes.commands.arguments.ArgumentResidentArray
import net.aechronis.nodes.commands.arguments.ArgumentSanitizedString
import net.aechronis.nodes.commands.arguments.ArgumentTerritory
import net.aechronis.nodes.commands.arguments.ArgumentTerritoryArray
import net.aechronis.nodes.commands.arguments.ArgumentTown
import net.aechronis.nodes.commands.arguments.ArgumentTownArray
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.Warzone
import net.minestom.server.command.builder.arguments.ArgumentType

class NodesAdminTownCommand : NodesCommand("town", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "${ChatColor.BOLD}[Nodes] Admin town management:")
            Message.print(player, "/nodesadmin town create${ChatColor.WHITE}: Create a new town")
            Message.print(player, "/nodesadmin town delete${ChatColor.WHITE}: Delete existing town")
            Message.print(player, "/nda town merge <townA> <townB>${ChatColor.WHITE}: Move all Town B territories into Town A and delete Town B")
            Message.print(player, "/nda town move <townA> <townB>${ChatColor.WHITE}: Move Town B's residents into Town A")
            Message.print(player, "/nda town lives <town-name> <number>${ChatColor.WHITE}: Set a town's remaining lives")
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
            Message.print(player, "/nda town coatofarms <town-name> <url|clear>${ChatColor.WHITE}: Set map coat of arms")
            Message.print(player, "/nodesadmin town income${ChatColor.WHITE}: View a town's income inventory")
            Message.print(player, "/nodesadmin town plot${ChatColor.WHITE}: Manage a town's plots")
            Message.print(player, "/nodesadmin town ai${ChatColor.WHITE}: Configure AI defenders for a town")
            Message.print(player, "Run a command with no args to see usage.")
        }

        addSubcommand(NodesAdminTownCreateCommand())
        addSubcommand(NodesAdminTownDeleteCommand())
        addSubcommand(NodesAdminTownMergeCommand())
        addSubcommand(NodesAdminTownMoveCommand())
        addSubcommand(NodesAdminTownLivesCommand())
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
        addSubcommand(NodesAdminTownCoatOfArmsCommand())
        addSubcommand(NodesAdminTownIncomeCommand())
        addSubcommand(NodesAdminTownSetHomeCommand())
        addSubcommand(NodesAdminTownDefaultTownSpawnsCommand())
        addSubcommand(NodesAdminTownPlotCommand())
        addSubcommand(NodesAdminTownAiCommand())
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
            val town = context[townArg]
            if (Warzone.ownsRegisteredZone(town)) {
                Message.error(player, "Cannot delete ${town.name}: warzone territories must remain inside a town")
                return@addSyntax
            }
            Town.destroy(town)
            Message.print(player, "Town \"${town.name}\" has been deleted")
        }, townArg)
    }
}

class NodesAdminTownMergeCommand : NodesCommand("merge", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nda town merge <townA> <townB>")
        }

        val destinationArg = ArgumentTown.create("townA")
        val sourceArg = ArgumentTown.create("townB")

        addSyntax({ player, _, context ->
            val destination = context[destinationArg]
            val source = context[sourceArg]
            if (destination === source) {
                Message.error(player, "Town A and Town B must be different towns")
                return@addSyntax
            }

            val moved = Town.merge(destination, source)
            Message.print(player, "Merged all $moved territories from ${source.name} into ${destination.name} and deleted ${source.name}")
        }, destinationArg, sourceArg)
    }
}

class NodesAdminTownMoveCommand : NodesCommand("move", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nda town move <townA> <townB>")
        }

        val destinationArg = ArgumentTown.create("townA")
        val sourceArg = ArgumentTown.create("townB")

        addSyntax({ player, _, context ->
            val destination = context[destinationArg]
            val source = context[sourceArg]
            if (destination === source) {
                Message.error(player, "Town A and Town B must be different towns")
                return@addSyntax
            }

            val moved = Town.moveResidents(destination, source)
            Message.print(player, "Moved $moved residents from ${source.name} to ${destination.name} as regular residents")
        }, destinationArg, sourceArg)
    }
}

class NodesAdminTownLivesCommand : NodesCommand("lives", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nda town lives <town-name> <number>")
        }

        val townArg = ArgumentTown.create("town-name")
        val livesArg = ArgumentType.Integer("number")

        addSyntax({ player, _, context ->
            val lives = context[livesArg]
            if (lives < 1) {
                Message.error(player, "Town lives must be at least 1")
                return@addSyntax
            }

            val town = context[townArg]
            Town.setLives(town, lives)
            Message.print(player, "Set ${town.name}'s remaining lives to $lives")
        }, townArg, livesArg)
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
                if (Town.addResident(context[townArg], resident, bypassTestTownSelection = true, bypassJoinRestrictions = true)) {
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
            val territories = context[territoriesArg]
            val warzones = territories.filter(Warzone::isRegistered)
            if (warzones.isNotEmpty()) {
                Message.error(player, "Territories have a scheduled or running warzone: ${warzones.joinToString(", ") { it.id.toString() }}")
                return@addSyntax
            }
            for (terr in territories) {
                Town.unclaim(context[townArg], terr)
            }

            Message.print(player, "Removed ${territories.size} territories from town \"${context[townArg].name}\"")
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
                val terrHome = Territory.fromId(town.home)
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
            Nodes.markWorldDirty()
        }, townsArg)
    }
}

class NodesAdminTownAiCommand : NodesCommand("ai", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "[Nodes] AI town configuration:")
            Message.print(player, "/nodesadmin town ai show <town>")
            Message.print(player, "/nodesadmin town ai set <town> <field> <value>")
            Message.print(player, "Fields: controlled, enemies, guns")
            Message.print(player, "Gun IDs are comma-separated; use 'none' for no guns")
            Message.print(player, "/nodesadmin town ai clear <town>")
            Message.print(
                player,
                "Defender changes apply to the next campaign; disabling AI control ends active colonization",
            )
        }

        addSubcommand(NodesAdminTownAiShowCommand())
        addSubcommand(NodesAdminTownAiSetCommand())
        addSubcommand(NodesAdminTownAiClearCommand())
    }
}

class NodesAdminTownAiShowCommand : NodesCommand("show", "nodes.admin") {
    init {
        val townArg = ArgumentTown.create("town-name")
        addSyntax({ player, _, context -> context[townArg].printAiConfig(player) }, townArg)
    }
}

class NodesAdminTownAiSetCommand : NodesCommand("set", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nodesadmin town ai set <town> <field> <value>")
        }

        val townArg = ArgumentTown.create("town-name")
        val fieldArg = ArgumentType.Word("field").from(
            "controlled",
            "enemies",
            "guns",
        )
        val valueArg = ArgumentType.String("value")

        addSyntax({ player, _, context ->
            val town = context[townArg]
            val field = context[fieldArg]
            val value = context[valueArg]
            val current = town.aiConfig
            val updated = runCatching {
                when (field) {
                    "controlled" -> current.copy(controlled = value.toBooleanStrict())

                    "enemies" -> current.copy(enemyCount = value.toInt())

                    "guns" -> current.copy(
                        guns = if (value.equals("none", ignoreCase = true)) {
                            emptyList()
                        } else {
                            value.split(',').map(String::trim)
                        },
                    )

                    else -> error("Unknown AI configuration field '$field'")
                }.also(AiTownConfig::requireRegisteredGuns)
            }.getOrElse { error ->
                Message.error(player, "Invalid AI town configuration: ${error.message}")
                return@addSyntax
            }

            Town.setAiConfig(town, updated)
            Message.print(player, "Updated ${town.name} AI field '$field'")
            if (field == "controlled" && !updated.controlled) {
                Message.print(player, "Active colonization against ${town.name} will end")
            } else {
                Message.print(player, "Active colonization defenses keep their previous defender configuration")
            }
            town.printAiConfig(player)
        }, townArg, fieldArg, valueArg)
    }
}

class NodesAdminTownAiClearCommand : NodesCommand("clear", "nodes.admin") {
    init {
        val townArg = ArgumentTown.create("town-name")
        addSyntax({ player, _, context ->
            val town = context[townArg]
            Town.setAiConfig(town, AiTownConfig())
            Message.print(player, "Cleared AI control and defender configuration for ${town.name}")
            Message.print(player, "Active colonization against ${town.name} will end")
        }, townArg)
    }
}

class NodesAdminTownCoatOfArmsCommand : NodesCommand("coatofarms", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nda town coatofarms <town-name> <url|clear>")
        }
        val townArg = ArgumentTown.create("town-name")
        val urlArg = ArgumentType.Word("url")
        addSyntax({ player, resident, context ->
            val url = context[urlArg].takeUnless { it.equals("clear", ignoreCase = true) }
            if (url != null && !validMapImageUrl(url)) {
                Message.error(player, "Use an HTTP(S) image URL of at most 2048 characters, or clear")
                return@addSyntax
            }
            val town = context[townArg]
            Town.setCoatOfArmsUrl(town, url)
            Message.print(player, if (url == null) "Cleared ${town.name}'s map coat of arms" else "Set ${town.name}'s map coat of arms to $url")
        }, townArg, urlArg)
    }
}
