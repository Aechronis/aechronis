package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.commands.arguments.ArgumentSanitizedString
import net.aechronis.nodes.objects.ActiveBuilding
import net.aechronis.nodes.objects.ActiveBuildings
import net.aechronis.nodes.objects.Building
import net.aechronis.nodes.objects.Farm
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.OilRig
import net.aechronis.nodes.objects.Port
import net.aechronis.nodes.objects.TrainStationBuilding
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.command.builder.arguments.ArgumentBoolean
import net.minestom.server.command.builder.arguments.ArgumentType

class NodesAdminBuildingCommand : NodesCommand("building", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "${ChatColor.AQUA}/nodesadmin building create${ChatColor.WHITE}: Create a new building")
            Message.print(player, "${ChatColor.AQUA}/nodesadmin building delete${ChatColor.WHITE}: Delete the building in your current chunk")
            Message.print(player, "${ChatColor.AQUA}/nodesadmin building settier${ChatColor.WHITE}: Set tier of the building in your current chunk")
            Message.print(player, "${ChatColor.AQUA}/nda building setprogress${ChatColor.WHITE}: Set active factory production progress in your current chunk")
            Message.print(player, "Run a command with no args to see usage.")
        }

        addSubcommand(NodesAdminBuildingCreateCommand())
        addSubcommand(NodesAdminBuildingDeleteCommand())
        addSubcommand(NodesAdminBuildingSetTierCommand())
        addSubcommand(NodesAdminBuildingSetProgressCommand())
    }
}

class NodesAdminBuildingCreateCommand : NodesCommand("create", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage:")
            Message.print(player, "/nodesadmin building create port <name> <public> [tier]")
            Message.print(player, "/nodesadmin building create farm [tier]")
            Message.print(player, "/nodesadmin building create oilrig [tier]")
            Message.print(player, "/nodesadmin building create train [tier]")
            Message.print(player, "/nodesadmin building create active <type> <tier> (stand at the output location)")
            Message.print(player, "Active types: ${Nodes.config.activeBuildings.joinToString { it.name }}")
        }

        val activeLit = ArgumentType.Literal("active")
        val activeTypeArg = ArgumentType.Word("type")
        val portLit = ArgumentType.Literal("port")
        val farmLit = ArgumentType.Literal("farm")
        val oilRigLit = ArgumentType.Literal("oilrig")
        val trainLit = ArgumentType.Literal("train")
        val nameArg = ArgumentSanitizedString.create("name")
        val publicArg = ArgumentBoolean("public")
        val tierArg = ArgumentType.Integer("tier").between(1, 3)

        addSyntax({ player, resident, context ->
            val instance = player.instance ?: return@addSyntax
            ActiveBuilding.create(context[activeTypeArg], instance.getDimensionName(), player.position.asBlockVec(), context[tierArg])
                .onSuccess { Message.print(player, "Approved ${it.definitionName} (tier ${it.tier}); use /t buildings to operate it") }
                .onFailure { Message.error(player, it.message ?: "Unable to create active building") }
        }, activeLit, activeTypeArg, tierArg)

        addSyntax({ player, resident, context ->
            Port.create(
                context[nameArg],
                Math.floorDiv(player.position.blockX(), 16),
                Math.floorDiv(player.position.blockZ(), 16),
                context[tierArg],
                context[publicArg],
            ).getOrElse { err ->
                Message.error(player, "Failed to create port: ${err.message}")
                return@addSyntax
            }
            Message.print(player, "Created port \"${context[nameArg]}\" (tier ${context[tierArg]})")
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

        addSyntax({ player, resident, context ->
            OilRig.create(
                Math.floorDiv(player.position.blockX(), 16),
                Math.floorDiv(player.position.blockZ(), 16),
                context[tierArg],
            ).getOrElse { err ->
                Message.error(player, "Failed to create oil rig: ${err.message}")
                return@addSyntax
            }
            Message.print(player, "Created oil rig (tier $context[tierArg])")
        }, oilRigLit, tierArg)

        addSyntax({ player, resident, context ->
            TrainStationBuilding.create(
                Math.floorDiv(player.position.blockX(), 16),
                Math.floorDiv(player.position.blockZ(), 16),
                context[tierArg],
            ).getOrElse { err ->
                Message.error(player, "Failed to create train station: ${err.message}")
                return@addSyntax
            }
            Message.print(player, "Created train station (tier $context[tierArg])")
        }, trainLit, tierArg)
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
            if (building is ActiveBuilding && building.production != null) {
                Message.error(player, "Wait for production to finish before deleting this building")
                return@setDefaultExecutor
            }
            if (building is ActiveBuilding && building.inputItems().any { !it.isAir }) {
                Message.error(player, "Empty the building's input inventory before deleting it")
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
            if (building is ActiveBuilding && building.production != null) {
                Message.error(player, "Wait for production to finish before changing its tier")
                return@addSyntax
            }
            Building.setTier(building, context[tierArg])
            Message.print(player, "${building.type} in chunk (${building.chunkX}, ${building.chunkZ}) set to tier ${building.tier}")
        }, tierArg)
    }
}

class NodesAdminBuildingSetProgressCommand : NodesCommand("setprogress", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nda building setprogress <0-100>")
            Message.print(player, "Stand in the active factory's chunk. It must already be producing.")
        }

        val progressArg = ArgumentType.Integer("percent").between(0, 100)
        addSyntax({ player, resident, context ->
            val building = buildingAtPlayer(player) as? ActiveBuilding
            if (building == null || building.world != player.instance?.getDimensionName()) {
                Message.error(player, "No active factory in this chunk")
                return@addSyntax
            }
            val percent = context[progressArg]
            if (!ActiveBuildings.setProgress(building, percent)) {
                Message.error(player, "This factory is idle; start a recipe through /t buildings first")
                return@addSyntax
            }
            Message.print(player, "${building.definitionName} production progress set to $percent%")
            if (percent == 100) Message.print(player, "Output will drop on the next production update once the chunk is loaded")
        }, progressArg)
    }
}
