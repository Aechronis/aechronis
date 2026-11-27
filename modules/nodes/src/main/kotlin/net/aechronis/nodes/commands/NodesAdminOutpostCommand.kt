package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.commands.arguments.ArgumentTerritory
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Outposts

class NodesAdminOutpostCommand : NodesCommand("outpost", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, _ ->
            Message.print(sender, "Usage: /nda outpost add|remove <territory-id>, /nda outpost list")
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
        setSenderDefaultExecutor { sender, _ -> Message.print(sender, "Usage: /nda outpost $name <territory-id>") }
        val territoryArg = ArgumentTerritory.create("territory-id")
        addSenderSyntax({ sender, context ->
            val territory = context[territoryArg]
            change(territory)
                .onSuccess { Message.print(sender, "Territory ${territory.id} $success") }
                .onFailure { error -> Message.error(sender, error.message ?: "Failed") }
        }, territoryArg)
    }
}

private class OutpostListCommand : NodesCommand("list", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, _ ->
            val ids = Outposts.all()
            Message.print(sender, if (ids.isEmpty()) "No mining outposts" else "Mining outposts: ${ids.joinToString(", ")}")
        }
    }
}
