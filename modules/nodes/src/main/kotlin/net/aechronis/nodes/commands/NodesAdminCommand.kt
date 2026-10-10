package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.entity.Player

class NodesAdminCommand : NodesCommand("nodesadmin", "nodes.admin", "nda") {
    init {
        setDefaultExecutor { player, resident, context ->
            printNodesAdminHelp(player)
        }

        addSubcommand(NodesAdminHelpCommand())
        addSubcommand(NodesAdminWarCommand())
        addSubcommand(NodesAdminTownCommand())
        addSubcommand(NodesAdminNationCommand())
        addSubcommand(NodesAdminResidentCommand())
        addSubcommand(NodesAdminBuildingCommand())
        addSubcommand(NodesAdminTrainsCommand())
        addSubcommand(NodesAdminTeleportCommand())
        addSubcommand(NodesAdminReasorceCommand())
        addSubcommand(NodesAdminSaveCommand())
        addSubcommand(NodesAdminLoadCommand())
        addSubcommand(NodesAdminRunIncomeCommand())
        addSubcommand(NodesAdminMiningBoostCommand())
        addSubcommand(NodesAdminWarzoneCommand())
    }
}

class NodesAdminHelpCommand : NodesCommand("help", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            printNodesAdminHelp(player)
        }
    }
}

private fun printNodesAdminHelp(sender: Player) {
    Message.print(sender, "[Nodes] Admin commands:")
    Message.print(sender, "/nodesadmin war${ChatColor.WHITE}: Enable/disable war")
    Message.print(sender, "/nodesadmin warzone${ChatColor.WHITE}: Manage warzones")
    Message.print(sender, "/nodesadmin town${ChatColor.WHITE}: Manage towns (see \"/nodesadmin town\")")
    Message.print(sender, "/nodesadmin nation${ChatColor.WHITE}: Manage nations (see \"/nodesadmin nation\")")
    Message.print(sender, "/nodesadmin resident${ChatColor.WHITE}: Manage resident cooldowns")
    Message.print(sender, "/nodesadmin building${ChatColor.WHITE}: Manage buildings (see \"/nodesadmin building\")")
    Message.print(sender, "/nda trains${ChatColor.WHITE}: Manage train stations")
    Message.print(sender, "/nda teleport <territory-id>${ChatColor.WHITE}: Teleport to a territory's core chunk")
    Message.print(sender, "/nodesadmin reasorce${ChatColor.WHITE}: Add or remove a territory resource node")
    Message.print(sender, "/nodesadmin save${ChatColor.WHITE}: Force save world")
    Message.print(sender, "/nodesadmin load${ChatColor.WHITE}: Force load world")
    Message.print(sender, "/nodesadmin runincome${ChatColor.WHITE}: Runs income for all towns")
    Message.print(sender, "/nodesadmin miningboost${ChatColor.WHITE}: Configure global mining boosts")
    Message.print(sender, "/nodesadmin resident removecooldown <player-names>${ChatColor.WHITE}: Clear town-join cooldowns")
    Message.print(sender, "/nda resident leavepenalty <allow|deny>${ChatColor.WHITE}: Toggle the global town-leave cooldown")
}
