package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.TreeFeller
import net.aechronis.vanilla.managers.TreeFeller.getTree
import net.aechronis.vanilla.managers.TreeFeller.isLog
import net.aechronis.vanilla.managers.TreeFeller.isTree
import net.minestom.server.entity.GameMode
import net.minestom.server.event.player.PlayerBlockBreakEvent

object TreeFellerListener {
    fun onBreakLog(event: PlayerBlockBreakEvent) {
        val player = event.player
        if (player.gameMode != GameMode.SURVIVAL) return
        val block = event.block
        if (!isLog(block)) return
        val instance = player.instance ?: return
        if (!isTree(event.blockPosition, instance, block)) return
        val tree = getTree(event.blockPosition, instance, block)
        if (tree.isEmpty()) return

        val logs = tree.drop(1)
        val leaves = if (Vanilla.config!!.treeFellerBreakLeaves) TreeFeller.collectLeaves(tree, instance) else emptyList()
        TreeFeller.fell(player, instance, logs, leaves, block)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, TreeFellerListener::onBreakLog)
    }
}
