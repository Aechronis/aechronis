package net.aechronis.nodes.listeners

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Plot
import net.aechronis.nodes.objects.Resident
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerStartDiggingEvent

object NodesPlotSelectionListener {
    private fun selectFirstCorner(player: Player, block: BlockVec): Boolean {
        val resident = Resident.fromPlayer(player) ?: return false
        if (!resident.plotSelectionEnabled) return false

        resident.plotCornerOne = Plot.BlockVec3(block.blockX, block.blockY, block.blockZ)
        resident.plotCornerTwo = null
        Message.print(player, "Selected plot corner 1 at (${block.blockX}, ${block.blockY}, ${block.blockZ})")
        return true
    }

    private fun onStartDigging(event: PlayerStartDiggingEvent) {
        if (selectFirstCorner(event.player, event.blockPosition)) event.isCancelled = true
    }

    private fun onBlockBreak(event: PlayerBlockBreakEvent) {
        if (event.isCancelled) return
        if (selectFirstCorner(event.player, event.blockPosition)) event.isCancelled = true
    }

    private fun onBlockInteract(event: PlayerBlockInteractEvent) {
        val resident: Resident = Resident.fromPlayer(event.player) ?: return
        if (!resident.plotSelectionEnabled) return

        val first = resident.plotCornerOne
        if (first == null) {
            Message.error(event.player, "Select plot corner 1 with left-click first")
        } else {
            val block = event.blockPosition
            resident.plotCornerTwo = Plot.BlockVec3(block.blockX, block.blockY, block.blockZ)
            Message.print(event.player, "Selected plot corner 2 at (${block.blockX}, ${block.blockY}, ${block.blockZ})")
        }

        event.isBlockingItemUse = true
        event.isCancelled = true
    }

    fun init() {
        Nodes.highPriorityEventNode.addListener(PlayerStartDiggingEvent::class.java, this::onStartDigging)
        Nodes.highPriorityEventNode.addListener(PlayerBlockBreakEvent::class.java, this::onBlockBreak)
        Nodes.highPriorityEventNode.addListener(PlayerBlockInteractEvent::class.java, this::onBlockInteract)
    }
}
