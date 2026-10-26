package net.aechronis.vanilla.listeners

import net.aechronis.server.modules.ModuleEvents
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.BlockDrops
import net.aechronis.vanilla.managers.Items
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.GameMode
import net.minestom.server.event.EventListener
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.component.EnchantmentList
import net.minestom.server.item.enchant.Enchantment

object PlayerBreakListener {
    // Emit ordinary drops after block interactions (0) and replacement drop listeners (1).
    private val eventNode =
        EventNode
            .all("vanilla-block-drops")
            .setPriority(2)
            .addListener(
                EventListener
                    .builder(PlayerBlockBreakEvent::class.java)
                    .ignoreCancelled(false)
                    .handler(::onBlockBreak)
                    .build(),
            )

    /** Rejects invalid mining before either the ordinary or custom drop listener grants items. */
    fun canBreak(event: PlayerBlockBreakEvent): Boolean {
        if (event.isCancelled) return false
        val player = event.player
        val blockCenter = event.blockPosition.asVec().add(0.5, 0.5, 0.5)
        if (player.position.distanceSquared(blockCenter) > 36.0) {
            event.isCancelled = true
            return false
        }
        if (player.gameMode == GameMode.CREATIVE || player.instance == null) return false
        val material = event.block.registry()?.material() ?: return false

        val config = Vanilla.config
        if (material in config.blocksConfig.blocksRequiringTool) {
            val heldMaterial = player.itemInMainHand.material()
            val canMine = config.blocksConfig.toolMinableBlocks[heldMaterial]?.contains(material) == true
            if (!canMine) {
                event.isCancelled = true
                return false
            }
        }
        return true
    }

    fun onBlockBreak(event: PlayerBlockBreakEvent) {
        val suppressed = BlockDrops.consumeSuppression(event)
        if (!canBreak(event)) return
        val player = event.player
        val instance = player.instance ?: return
        val heldItem = player.itemInMainHand

        if (!suppressed) {
            val material = event.block.registry()?.material() ?: return
            val config = Vanilla.config.blocksConfig
            val hasSilkTouch = heldItem.get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY).has(Enchantment.SILK_TOUCH)
            val drops =
                if (hasSilkTouch && material in config.blocksSilkTouchable) {
                    listOf(ItemStack.of(material))
                } else {
                    config.blockDrops[material] ?: listOf(ItemStack.of(material))
                }
            val position = event.blockPosition.add(0.5, 0.5, 0.5).asPos()
            for (stack in drops) {
                if (stack.isAir || stack.amount() <= 0) continue
                Items.spawn(instance, position, stack)
            }
        }

        val damagedTool = heldItem.damage(1)
        if (damagedTool != heldItem) player.itemInMainHand = damagedTool
    }

    fun init() {
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), eventNode)
    }
}
