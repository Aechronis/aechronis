package net.aechronis.nodes.commands

import net.aechronis.nodes.objects.FlagsMenu
import net.aechronis.nodes.objects.NodesCommand

class FlagsCommand : NodesCommand("flags") {
    init {
        setDefaultExecutor { player, _, _ -> FlagsMenu.open(player) }
    }
}
