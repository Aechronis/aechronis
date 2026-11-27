package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.commands.arguments.ArgumentResourceNode
import net.aechronis.nodes.commands.arguments.ArgumentTerritory
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Territory
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType

class NodesAdminTeleportCommand : NodesCommand("teleport", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nda teleport <territory-id>")
        }

        val territoryArg = ArgumentTerritory.create("territory-id")

        addSyntax({ player, resident, context ->
            val territory = context[territoryArg]
            val instance = MinecraftServer.getInstanceManager().instances.firstOrNull()
            if (instance == null) {
                Message.error(player, "The world is unavailable")
                return@addSyntax
            }

            // Ensure the terrain is available before finding a position above its surface.
            instance.loadChunk(territory.core.x, territory.core.z).join()
            val destination = Territory.defaultSpawnLocation(territory)
                .withView(player.position.yaw, player.position.pitch)
            player.teleport(destination)
            Message.print(player, "Teleported to territory id=${territory.id} core chunk")
        }, territoryArg)
    }
}

class NodesAdminReasorceCommand : NodesCommand("reasorce", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin reasorce <remove|add> <territory-id> <reasorce-node>")
        }

        val territoryArg = ArgumentTerritory.create("territory-id")
        val resourceNodeArg = ArgumentResourceNode.create("reasorce-node")
        val addArg = ArgumentType.Literal("add")
        val removeArg = ArgumentType.Literal("remove")

        addSyntax({ player, resident, context ->
            updateResourceNode(player, context[territoryArg], context[resourceNodeArg], true)
        }, addArg, territoryArg, resourceNodeArg)

        addSyntax({ player, resident, context ->
            updateResourceNode(player, context[territoryArg], context[resourceNodeArg], false)
        }, removeArg, territoryArg, resourceNodeArg)
    }

    private fun updateResourceNode(
        player: net.minestom.server.entity.Player,
        territory: Territory,
        resourceNode: net.aechronis.nodes.objects.ResourceNode,
        add: Boolean,
    ) {
        Nodes.updateTerritoryResourceNode(territory.id, resourceNode.name, add)
            .onSuccess {
                val action = if (add) "Added" else "Removed"
                Message.print(player, "$action resource node \"${resourceNode.name}\" ${if (add) "to" else "from"} territory id=${territory.id}")
            }
            .onFailure { error ->
                Message.error(player, error.message ?: "Failed to update territory resource node")
            }
    }
}

class NodesAdminSaveCommand : NodesCommand("save", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage:")
            Message.print(sender, "/nodesadmin save")
            Message.print(sender, "/nodesadmin save <sync>")
        }

        val syncArg = ArgumentType.Boolean("sync")

        addSenderSyntax({ sender, context ->
            Message.print(sender, "[Nodes] Saving world (async)")
            Nodes.saveWorld(checkIfNeedsSave = false, async = true)
        })

        addSenderSyntax({ sender, context ->
            if (context[syncArg]) {
                Message.print(sender, "[Nodes] Saving world (sync)")
                Nodes.saveWorld(checkIfNeedsSave = false, async = false)
            } else {
                Message.print(sender, "[Nodes] Saving world (async)")
                Nodes.saveWorld(checkIfNeedsSave = false, async = true)
            }
        }, syncArg)
    }
}

class NodesAdminLoadCommand : NodesCommand("load", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin load")
        }

        addSenderSyntax({ sender, context ->
            Message.print(sender, "[Nodes] Loading world")
            try {
                Nodes.loadWorld()
            } catch (error: Exception) {
                System.err.println("[Nodes] Failed to reload world: ${error.message}")
                error.printStackTrace()
                Message.error(sender, "Failed to load world: ${error.message}. Saving is suspended; correct the data and run /nda load again.")
            }
        })
    }
}

class NodesAdminRunIncomeCommand : NodesCommand("runincome", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin runincome")
        }

        addSenderSyntax({ sender, context ->
            Message.print(sender, "Running incomes for all towns")
            Nodes.runIncome()
        })
    }
}
