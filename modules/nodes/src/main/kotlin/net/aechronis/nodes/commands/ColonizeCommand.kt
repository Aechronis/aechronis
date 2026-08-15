package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.colonization.Colonization
import net.aechronis.nodes.colonization.ColonizationMenu
import net.aechronis.nodes.colonization.canStartColonization
import net.aechronis.nodes.objects.NodesCommand

class ColonizeCommand : NodesCommand("colonize") {
    init {
        setDefaultExecutor { player, resident, _ ->
            val town = resident.town
            if (town == null) {
                Colonization.clearSelection(player)
                Message.error(player, "You must be in a town to colonize")
                return@setDefaultExecutor
            }
            if (town.nation == null) {
                Colonization.clearSelection(player)
                Message.error(player, "You must be in a nation to colonize")
                return@setDefaultExecutor
            }
            if (!canStartColonization(resident, town)) {
                Colonization.clearSelection(player)
                Message.error(player, "You must be a town officer or town leader in your nation to colonize")
                return@setDefaultExecutor
            }
            ColonizationMenu.openNations(player)
        }
    }
}
