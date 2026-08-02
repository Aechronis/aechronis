package net.aechronis.nodes.listeners

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Plot
import net.aechronis.nodes.objects.Resident
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent

object NodesPlotSelectionListener {
    private fun onBlockBreak(event: PlayerBlockBreakEvent) {
        if (event.isCancelled) return

        val resident = Resident.fromPlayer(event.player) ?: return
        if (!resident.plotSelectionEnabled) return

        val block = event.blockPosition
        resident.plotCornerOne = Plot.BlockVec3(block.blockX, block.blockY, block.blockZ)
        resident.plotCornerTwo = null
        event.isCancelled = true
        Message.print(event.player, "Selected plot corner 1 at (${block.blockX}, ${block.blockY}, ${block.blockZ})")
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
        Nodes.highPriorityEventNode.addListener(PlayerBlockBreakEvent::class.java, this::onBlockBreak)
        Nodes.highPriorityEventNode.addListener(PlayerBlockInteractEvent::class.java, this::onBlockInteract)
    }
}
