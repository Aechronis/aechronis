package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.TreeFeller
import net.aechronis.vanilla.managers.TreeFeller.getTree
import net.aechronis.vanilla.managers.TreeFeller.isLog
import net.aechronis.vanilla.managers.TreeFeller.isTree
import net.aechronis.vanilla.utils.PlayerAddons.giveDrops
import net.minestom.server.entity.GameMode
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack

object TreeFellerBreakListener {
    fun onBreakLog(event: PlayerBlockBreakEvent) {
        val player = event.player
        if (player.gameMode != GameMode.SURVIVAL) return
        val block = event.block
        if (!isLog(block)) return
        val instance = player.instance ?: return
        if (!isTree(event.blockPosition, instance, block)) return
        val material = block.registry()?.material() ?: return
        val tree = getTree(event.blockPosition, instance, block)
        if (tree.isEmpty()) return
        for ((x, y, z) in tree.drop(1)) {
            instance.setBlock(x, y, z, Block.AIR)
            player.giveDrops(listOf(ItemStack.of(material)))
        }
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, TreeFellerBreakListener::onBreakLog)
    }
}
