package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.commands.arguments.ArgumentNation
import net.aechronis.nodes.commands.arguments.ArgumentSanitizedString
import net.aechronis.nodes.commands.arguments.ArgumentTown
import net.aechronis.nodes.commands.arguments.ArgumentTownArray
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.command.builder.arguments.ArgumentType

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
            Message.print(player, "/nda nation rallycap <number>${ChatColor.WHITE}: Set your nation's war login cap")
            Message.print(player, "/nodesadmin nation color${ChatColor.WHITE}: Set the color of a nation")
            Message.print(player, "/nda nation longname <nation-name> <name|clear>${ChatColor.WHITE}: Set map display name")
            Message.print(player, "/nda nation flag <nation-name> <url|clear>${ChatColor.WHITE}: Set map flag")
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
        addSubcommand(NodesAdminNationRallyCapCommand())
        addSubcommand(NodesAdminNationColorCommand())
        addSubcommand(NodesAdminNationLongNameCommand())
        addSubcommand(NodesAdminNationFlagCommand())
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

class NodesAdminNationRallyCapCommand : NodesCommand("rallycap", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nda nation rallycap <number>")
        }

        val capArg = ArgumentType.Integer("number")

        addSyntax({ player, _, _, nation, context ->
            val rallyCap = context[capArg]
            if (rallyCap < 1) {
                Message.error(player, "Rally cap must be at least 1")
                return@addSyntax
            }
            Nation.setRallyCap(nation, rallyCap)
            Message.print(player, "Set ${nation.name}'s war rally cap to $rallyCap online players")
        }, capArg)
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

class NodesAdminNationLongNameCommand : NodesCommand("longname", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nda nation longname <nation-name> <display name|clear>")
        }
        val nationArg = ArgumentNation.create("nation-name")
        val nameArg = ArgumentType.StringArray("display-name")
        addSyntax({ player, resident, context ->
            val value = context[nameArg].joinToString(" ").trim()
            if (value.isBlank() || value.length > 128 || value.any { it.isISOControl() || it == '§' }) {
                Message.error(player, "Use a display name of 1–128 characters without control or formatting codes")
                return@addSyntax
            }
            val nation = context[nationArg]
            val name = value.takeUnless { it.equals("clear", ignoreCase = true) }
            Nation.setLongName(nation, name)
            Message.print(player, if (name == null) "Cleared ${nation.name}'s map display name" else "Set ${nation.name}'s map display name to $name")
        }, nationArg, nameArg)
    }
}

class NodesAdminNationFlagCommand : NodesCommand("flag", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nda nation flag <nation-name> <url|clear>")
        }
        val nationArg = ArgumentNation.create("nation-name")
        val urlArg = ArgumentType.Word("url")
        addSyntax({ player, resident, context ->
            val url = context[urlArg].takeUnless { it.equals("clear", ignoreCase = true) }
            if (url != null && !validMapImageUrl(url)) {
                Message.error(player, "Use an HTTP(S) image URL of at most 2048 characters, or clear")
                return@addSyntax
            }
            val nation = context[nationArg]
            Nation.setFlagUrl(nation, url)
            Message.print(player, if (url == null) "Cleared ${nation.name}'s map flag" else "Set ${nation.name}'s map flag to $url")
        }, nationArg, urlArg)
    }
}
