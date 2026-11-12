package net.aechronis.nodes.commands

import net.aechronis.nodes.objects.FactoriesMenu
import net.aechronis.nodes.objects.NodesCommand

class FactoriesCommand : NodesCommand("factories") {
    init {
        setDefaultExecutor { player, _, _ -> FactoriesMenu.open(player) }
    }
}
