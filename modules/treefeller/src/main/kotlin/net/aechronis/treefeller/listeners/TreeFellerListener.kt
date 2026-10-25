package net.aechronis.treefeller.listeners

import net.aechronis.server.modules.ModuleEvents
import net.aechronis.treefeller.TreeFeller
import net.aechronis.treefeller.TreeFeller.getTree
import net.aechronis.treefeller.TreeFeller.isLog
import net.aechronis.treefeller.TreeFeller.isTree
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerBlockBreakEvent

object TreeFellerListener {
    // Inspect the intact tree after Nodes protection (-999/-998), before vanilla's drop handler (0).
    private val eventNode = EventNode.all("treefeller").setPriority(-1)

    fun onBreakLog(event: PlayerBlockBreakEvent) {
        if (event.isCancelled) return
        val player = event.player
        // This node runs before vanilla's reach check, so reject the same invalid breaks here.
        val blockCenter = event.blockPosition.asVec().add(0.5, 0.5, 0.5)
        if (player.position.distanceSquared(blockCenter) > 36.0) {
            event.isCancelled = true
            return
        }
        if (player.gameMode != GameMode.SURVIVAL) return
        val block = event.block
        if (!isLog(block)) return
        val instance = player.instance ?: return
        if (!isTree(event.blockPosition, instance, block)) return
        val tree = getTree(event.blockPosition, instance, block)
        if (tree.isEmpty()) return

        val logs = tree.drop(1)
        val leaves = if (TreeFeller.config.breakLeaves) TreeFeller.collectLeaves(tree, instance) else emptyList()
        TreeFeller.fell(player, instance, logs, leaves, block)
    }

    fun init() {
        eventNode.addListener(PlayerBlockBreakEvent::class.java, TreeFellerListener::onBreakLog)
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), eventNode)
    }
}
