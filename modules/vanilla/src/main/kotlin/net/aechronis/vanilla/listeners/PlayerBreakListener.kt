package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Items
import net.minestom.server.entity.GameMode
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.item.ItemStack

object PlayerBreakListener {
    fun onBlockBreak(event: PlayerBlockBreakEvent) {
        val player = event.player
        if (player.gameMode == GameMode.CREATIVE) return
        val instance = player.instance ?: return
        val material = event.block.registry()?.material() ?: return

        val drops = Vanilla.config!!.blockDrops[material] ?: listOf(ItemStack.of(material))
        val dropPos = event.blockPosition.add(0.5, 0.5, 0.5).asPos()
        for (stack in drops) {
            if (!stack.isAir && stack.amount() > 0) Items.spawn(instance, dropPos, stack)
        }
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, PlayerBreakListener::onBlockBreak)
    }
}
