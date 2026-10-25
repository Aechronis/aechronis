package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.commands.arguments.ArgumentTerritory
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Outposts

class NodesAdminOutpostCommand : NodesCommand("outpost", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nda outpost add|remove <territory-id>, /nda outpost list")
        }
        addSubcommand(OutpostChangeCommand("add", Outposts::add, "is now a mining outpost"))
        addSubcommand(OutpostChangeCommand("remove", Outposts::remove, "is no longer a mining outpost"))
        addSubcommand(OutpostListCommand())
    }
}

private class OutpostChangeCommand(
    name: String,
    private val change: (net.aechronis.nodes.objects.Territory) -> Result<Unit>,
    private val success: String,
) : NodesCommand(name, "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ -> Message.print(player, "Usage: /nda outpost $name <territory-id>") }
        val territoryArg = ArgumentTerritory.create("territory-id")
        addSyntax({ player, _, context ->
            val territory = context[territoryArg]
            change(territory)
                .onSuccess { Message.print(player, "Territory ${territory.id} $success") }
                .onFailure { error -> Message.error(player, error.message ?: "Failed") }
        }, territoryArg)
    }
}

private class OutpostListCommand : NodesCommand("list", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            val ids = Outposts.all()
            Message.print(player, if (ids.isEmpty()) "No mining outposts" else "Mining outposts: ${ids.joinToString(", ")}")
        }
    }
}
