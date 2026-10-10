package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.commands.arguments.ArgumentResidentArray
import net.aechronis.nodes.objects.NodesCommand
import net.minestom.server.command.builder.arguments.ArgumentType

class NodesAdminResidentCommand : NodesCommand("resident", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nda resident removecooldown <player-names>")
            Message.print(player, "Usage: /nda resident leavepenalty <allow|deny>")
        }

        addSubcommand(NodesAdminResidentRemoveCooldownCommand())
        addSubcommand(NodesAdminResidentLeavePenaltyCommand())
    }
}

class NodesAdminResidentRemoveCooldownCommand : NodesCommand("removecooldown", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nda resident removecooldown <player-names>")
        }

        val playersArg = ArgumentResidentArray.create("player-names")

        addSyntax({ player, _, context ->
            context[playersArg].forEach { resident ->
                resident.clearTownJoinCooldown()
                Message.print(player, "Removed town-join cooldown for \"${resident.name}\"")
            }
        }, playersArg)
    }
}

class NodesAdminResidentLeavePenaltyCommand : NodesCommand("leavepenalty", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nda resident leavepenalty <allow|deny>")
        }

        val flagArg = ArgumentType.Word("flag").from("allow", "deny")
        addSyntax({ player, _, context ->
            val enabled = context[flagArg] == "allow"
            Nodes.config.townLeavePenaltyEnabled = enabled
            val state = if (enabled) "enabled" else "disabled"
            Message.print(player, "Town leave penalty $state for future voluntary leaves")
        }, flagArg)
    }
}
