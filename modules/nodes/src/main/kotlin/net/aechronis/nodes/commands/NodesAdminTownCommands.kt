package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.colonization.AiTownConfig
import net.aechronis.nodes.commands.arguments.ArgumentResident
import net.aechronis.nodes.commands.arguments.ArgumentResidentArray
import net.aechronis.nodes.commands.arguments.ArgumentSanitizedString
import net.aechronis.nodes.commands.arguments.ArgumentTerritory
import net.aechronis.nodes.commands.arguments.ArgumentTerritoryArray
import net.aechronis.nodes.commands.arguments.ArgumentTown
import net.aechronis.nodes.commands.arguments.ArgumentTownArray
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.Warzone
import net.minestom.server.command.builder.arguments.ArgumentType

class NodesAdminTownCommand : NodesCommand("town", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "${ChatColor.BOLD}[Nodes] Admin town management:")
            Message.print(sender, "/nodesadmin town create${ChatColor.WHITE}: Create a new town")
            Message.print(sender, "/nodesadmin town delete${ChatColor.WHITE}: Delete existing town")
            Message.print(sender, "/nda town merge <townA> <townB>${ChatColor.WHITE}: Move all Town B territories into Town A and delete Town B")
            Message.print(sender, "/nda town move <townA> <townB>${ChatColor.WHITE}: Move Town B's residents into Town A")
            Message.print(sender, "/nda town lives <town-name> <number>${ChatColor.WHITE}: Set a town's remaining lives")
            Message.print(sender, "/nodesadmin town rename${ChatColor.WHITE}: Rename a town")
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
            Message.print(sender, "/nodesadmin town removeleader${ChatColor.WHITE}: Remove leader from a town")
            Message.print(sender, "/nda town coatofarms <town-name> <url|clear>${ChatColor.WHITE}: Set map coat of arms")
            Message.print(sender, "/nodesadmin town income${ChatColor.WHITE}: View a town's income inventory")
            Message.print(sender, "/nodesadmin town plot${ChatColor.WHITE}: Manage a town's plots")
            Message.print(sender, "/nodesadmin town ai${ChatColor.WHITE}: Configure AI defenders for a town")
            Message.print(sender, "Run a command with no args to see usage.")
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
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town create <town-name> <territory-ids>")
        }

        val townArg = ArgumentSanitizedString.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSenderSyntax({ sender, context ->
            // first territory is new town home
            val town = Town.create(context[townArg], context[territoriesArg][0], null).getOrElse { err ->
                Message.error(sender, "Failed to create town: ${err.message}")
                return@addSenderSyntax
            }

            // add the other territories
            for (i in 1 until context[territoriesArg].size) {
                Town.addTerritory(town, context[territoriesArg][i])
            }

            Message.print(sender, "Created town \"${context[townArg]}\" with ${context[territoriesArg].size} territories")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownDeleteCommand : NodesCommand("delete", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town delete <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSenderSyntax({ sender, context ->
            val town = context[townArg]
            if (Warzone.ownsRegisteredZone(town)) {
                Message.error(sender, "Cannot delete ${town.name}: warzone territories must remain inside a town")
                return@addSenderSyntax
            }
            Town.destroy(town)
            Message.print(sender, "Town \"${town.name}\" has been deleted")
        }, townArg)
    }
}

class NodesAdminTownMergeCommand : NodesCommand("merge", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, _ ->
            Message.print(sender, "Usage: /nda town merge <townA> <townB>")
        }

        val destinationArg = ArgumentTown.create("townA")
        val sourceArg = ArgumentTown.create("townB")

        addSenderSyntax({ sender, context ->
            val destination = context[destinationArg]
            val source = context[sourceArg]
            if (destination === source) {
                Message.error(sender, "Town A and Town B must be different towns")
                return@addSenderSyntax
            }

            val moved = Town.merge(destination, source)
            Message.print(sender, "Merged all $moved territories from ${source.name} into ${destination.name} and deleted ${source.name}")
        }, destinationArg, sourceArg)
    }
}

class NodesAdminTownMoveCommand : NodesCommand("move", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, _ ->
            Message.print(sender, "Usage: /nda town move <townA> <townB>")
        }

        val destinationArg = ArgumentTown.create("townA")
        val sourceArg = ArgumentTown.create("townB")

        addSenderSyntax({ sender, context ->
            val destination = context[destinationArg]
            val source = context[sourceArg]
            if (destination === source) {
                Message.error(sender, "Town A and Town B must be different towns")
                return@addSenderSyntax
            }

            val moved = Town.moveResidents(destination, source)
            Message.print(sender, "Moved $moved residents from ${source.name} to ${destination.name} as regular residents")
        }, destinationArg, sourceArg)
    }
}

class NodesAdminTownLivesCommand : NodesCommand("lives", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, _ ->
            Message.print(sender, "Usage: /nda town lives <town-name> <number>")
        }

        val townArg = ArgumentTown.create("town-name")
        val livesArg = ArgumentType.Integer("number")

        addSenderSyntax({ sender, context ->
            val lives = context[livesArg]
            if (lives < 1) {
                Message.error(sender, "Town lives must be at least 1")
                return@addSenderSyntax
            }

            val town = context[townArg]
            if (town.isAi && lives > 1) {
                Message.error(sender, "AI towns can only have 1 life")
                return@addSenderSyntax
            }
            Town.setLives(town, lives)
            Message.print(sender, "Set ${town.name}'s remaining lives to $lives")
        }, townArg, livesArg)
    }
}

class NodesAdminTownRenameCommand : NodesCommand("rename", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town rename <town-name> <new-name>")
        }

        val townArg = ArgumentTown.create("town-name")
        val nameArg = ArgumentSanitizedString.create("new-name")

        addSenderSyntax({ sender, context ->
            Town.rename(context[townArg], context[nameArg])
            Message.print(sender, "${context[townArg].name} has been renamed to \"${context[nameArg]}\"")
        }, townArg, nameArg)
    }
}

class NodesAdminTownAddPlayerCommand : NodesCommand("addplayer", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town addplayer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSenderSyntax({ sender, context ->
            for (resident in context[playersArg]) {
                if (Town.addResident(context[townArg], resident, bypassTestTownSelection = true, bypassJoinRestrictions = true)) {
                    Message.print(sender, "Added \"${resident.name}\" to town \"${context[townArg].name}\"")
                } else {
                    Message.error(sender, "${resident.name} is already a member of a town")
                }
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownRemovePlayerCommand : NodesCommand("removeplayer", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town removeplayer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSenderSyntax({ sender, context ->
            for (resident in context[playersArg]) {
                Town.removeResident(context[townArg], resident)
                Message.print(sender, "Removed \"${resident.name}\" from town \"${context[townArg].name}\"")
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownAddTerritoryCommand : NodesCommand("addterritory", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town addterritory <town-name> <territory-ids>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSenderSyntax({ sender, context ->
            // add territories
            for (terr in context[territoriesArg]) {
                Town.addTerritory(context[townArg], terr)
            }

            Message.print(sender, "Added ${context[territoriesArg].size} territories to town \"${context[townArg].name}\"")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownRemoveTerritoryCommand : NodesCommand("removeterritory", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town removeterritory <town-name> <territory-ids>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSenderSyntax({ sender, context ->
            val territories = context[territoriesArg]
            val warzones = territories.filter(Warzone::isRegistered)
            if (warzones.isNotEmpty()) {
                Message.error(sender, "Territories have a scheduled or running warzone: ${warzones.joinToString(", ") { it.id.toString() }}")
                return@addSenderSyntax
            }
            for (terr in territories) {
                Town.unclaim(context[townArg], terr)
            }

            Message.print(sender, "Removed ${territories.size} territories from town \"${context[townArg].name}\"")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownCaptureTerritoryCommand : NodesCommand("captureterritory", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town captureterritory <town-name> <territory-ids>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSenderSyntax({ sender, context ->
            // add territories
            for (terr in context[territoriesArg]) {
                Town.capture(context[townArg], terr)
            }

            Message.print(sender, "Captured ${context[territoriesArg].size} territories for town \"${context[townArg].name}\"")
        }, townArg, territoriesArg)
    }
}

class NodesAdminTownReleaseTerritoryCommand : NodesCommand("releaseterritory", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town releaseterritory <territory-ids>")
        }

        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")

        addSenderSyntax({ sender, context ->
            // add territories
            for (terr in context[territoriesArg]) {
                Town.release(terr)
            }

            Message.print(sender, "Released ${context[territoriesArg].size} territories under occupation")
        }, territoriesArg)
    }
}

class NodesAdminTownAddOfficerCommand : NodesCommand("addofficer", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town addofficer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSenderSyntax({ sender, context ->
            // make residents officers
            for (r in context[playersArg]) {
                Town.addOfficer(context[townArg], r)
                Message.print(sender, "Made \"${r.name}\" officer of \"${context[townArg].name}\"")
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownRemoveOfficerCommand : NodesCommand("removeofficer", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town removeofficer <town-name> <player-names>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playersArg = ArgumentResidentArray.create("player-names")

        addSenderSyntax({ sender, context ->
            // make residents officers
            for (r in context[playersArg]) {
                Town.removeOfficer(context[townArg], r)
                Message.print(sender, "Removed \"${r.name}\" as officer of \"${context[townArg].name}\"")
            }
        }, townArg, playersArg)
    }
}

class NodesAdminTownLeaderCommand : NodesCommand("leader", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town leader <town-name> <player-name>")
        }

        val townArg = ArgumentTown.create("town-name")
        val playerArg = ArgumentResident.create("player-name")

        addSenderSyntax({ sender, context ->
            if (context[playerArg].town !== context[townArg]) {
                Message.error(sender, "Player \"${context[playerArg].name}\" is not a member of \"${context[townArg].name}\"")
                return@addSenderSyntax
            }

            Town.setLeader(context[townArg], context[playerArg])
            Message.print(sender, "Player \"${context[playerArg].name}\" is now leader of \"${context[townArg].name}\"")
        }, townArg, playerArg)
    }
}

class NodesAdminTownRemoveLeaderCommand : NodesCommand("removeleader", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town removeleader <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSenderSyntax({ sender, context ->
            Town.setLeader(context[townArg], null)
            Message.print(sender, "Removed leader of \"${context[townArg].name}\"")
        }, townArg)
    }
}

class NodesAdminTownIncomeCommand : NodesCommand("income", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town income <town-name>")
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
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town setspawn <town-name>")
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
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town spawn <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, context ->
            player.teleport(context[townArg].spawnpoint)
        }, townArg)
    }
}

class NodesAdminTownSetHomeCommand : NodesCommand("sethome", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town sethome <town-name> <territory-id>")
        }

        val townArg = ArgumentTown.create("town-name")
        val territoryArg = ArgumentTerritory.create("territory-id")

        addSenderSyntax({ sender, context ->
            // set town home territory
            if (context[townArg] !== context[territoryArg].town) {
                Message.error(sender, "Invalid territory id=${context[territoryArg].id}: does not belong to town")
                return@addSenderSyntax
            }

            if (context[townArg].home == context[territoryArg].id) {
                Message.error(sender, "Invalid territory id=${context[territoryArg].id}: already is home territory")
                return@addSenderSyntax
            }

            Town.setHome(context[townArg], context[territoryArg])
            Message.print(sender, "Moved \"${context[townArg].name}\" home territory to id = ${context[territoryArg].id}")
        }, townArg, territoryArg)
    }
}

class NodesAdminTownDefaultTownSpawnsCommand : NodesCommand("defaulttownspawns", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin town defaulttownspawns <town-names>")
        }

        val townsArg = ArgumentTownArray.create("town-names")

        addSenderSyntax({ sender, context ->
            // set town home territory
            for (town in context[townsArg]) {
                val spawnpoint = Town.resetSpawn(town)
                if (spawnpoint != null) {
                    Message.print(sender, "Set town \"${town.name}\" spawnpoint to $spawnpoint")
                } else {
                    Message.error(sender, "Town \"${town.name}\" home territory ${town.home} does not exist")
                }
            }
        }, townsArg)
    }
}

class NodesAdminTownAiCommand : NodesCommand("ai", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, _ ->
            Message.print(sender, "[Nodes] AI town configuration:")
            Message.print(sender, "/nodesadmin town ai show <town>")
            Message.print(sender, "/nodesadmin town ai set <town> <field> <value>")
            Message.print(sender, "Fields: controlled, enemies, guns, throwables")
            Message.print(sender, "Gun and throwable IDs are comma-separated; use 'none' for no items")
            Message.print(sender, "/nodesadmin town ai clear <town>")
            Message.print(
                sender,
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
        addSenderSyntax({ sender, context -> context[townArg].printAiConfig(sender) }, townArg)
    }
}

class NodesAdminTownAiSetCommand : NodesCommand("set", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, _ ->
            Message.print(sender, "Usage: /nodesadmin town ai set <town> <field> <value>")
        }

        val townArg = ArgumentTown.create("town-name")
        val fieldArg = ArgumentType.Word("field").from(
            "controlled",
            "enemies",
            "guns",
            "throwables",
        )
        val valueArg = ArgumentType.String("value")

        addSenderSyntax({ sender, context ->
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

                    "throwables" -> current.copy(
                        throwables = if (value.equals("none", ignoreCase = true)) {
                            emptyList()
                        } else {
                            value.split(',').map(String::trim)
                        },
                    )

                    else -> error("Unknown AI configuration field '$field'")
                }.also(AiTownConfig::requireRegisteredItems)
            }.getOrElse { error ->
                Message.error(sender, "Invalid AI town configuration: ${error.message}")
                return@addSenderSyntax
            }

            Town.setAiConfig(town, updated)
            Message.print(sender, "Updated ${town.name} AI field '$field'")
            if (field == "controlled" && !updated.controlled) {
                Message.print(sender, "Active colonization against ${town.name} will end")
            } else {
                Message.print(sender, "Active colonization defenses keep their previous defender configuration")
            }
            town.printAiConfig(sender)
        }, townArg, fieldArg, valueArg)
    }
}

class NodesAdminTownAiClearCommand : NodesCommand("clear", "nodes.admin") {
    init {
        val townArg = ArgumentTown.create("town-name")
        addSenderSyntax({ sender, context ->
            val town = context[townArg]
            Town.setAiConfig(town, AiTownConfig())
            Message.print(sender, "Cleared AI control and defender configuration for ${town.name}")
            Message.print(sender, "Active colonization against ${town.name} will end")
        }, townArg)
    }
}

class NodesAdminTownCoatOfArmsCommand : NodesCommand("coatofarms", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nda town coatofarms <town-name> <url|clear>")
        }
        val townArg = ArgumentTown.create("town-name")
        val urlArg = ArgumentType.Word("url")
        addSenderSyntax({ sender, context ->
            val url = context[urlArg].takeUnless { it.equals("clear", ignoreCase = true) }
            if (url != null && !validMapImageUrl(url)) {
                Message.error(sender, "Use an HTTP(S) image URL of at most 2048 characters, or clear")
                return@addSenderSyntax
            }
            val town = context[townArg]
            Town.setCoatOfArmsUrl(town, url)
            Message.print(sender, if (url == null) "Cleared ${town.name}'s map coat of arms" else "Set ${town.name}'s map coat of arms to $url")
        }, townArg, urlArg)
    }
}
