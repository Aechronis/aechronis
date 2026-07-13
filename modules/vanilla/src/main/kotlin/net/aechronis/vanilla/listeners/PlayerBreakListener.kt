package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Items
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.GameMode
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.component.EnchantmentList
import net.minestom.server.item.enchant.Enchantment

object PlayerBreakListener {
    fun onBlockBreak(event: PlayerBlockBreakEvent) {
        val player = event.player
        if (player.gameMode == GameMode.CREATIVE) return
        val instance = player.instance ?: return
        val material = event.block.registry()?.material() ?: return

        val config = Vanilla.config
        val heldItem = player.itemInMainHand
        val hasSilkTouch = heldItem.get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY).has(Enchantment.SILK_TOUCH)
        val silkTouchApplies = hasSilkTouch && material in config.blocksSilkTouchable

        if (!silkTouchApplies && material in config.blocksRequiringTool) {
            val heldMaterial = heldItem.material()
            val canMine = config.toolMinableBlocks[heldMaterial]?.contains(material) == true
            if (!canMine) return
        }

        val drops =
            if (silkTouchApplies) {
                listOf(ItemStack.of(material))
            } else {
                config.blockDrops[material] ?: listOf(ItemStack.of(material))
            }
        val dropPos = event.blockPosition.add(0.5, 0.5, 0.5).asPos()
        for (stack in drops) {
            if (!stack.isAir && stack.amount() > 0) Items.spawn(instance, dropPos, stack)
        }
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, PlayerBreakListener::onBlockBreak)
    }
}
