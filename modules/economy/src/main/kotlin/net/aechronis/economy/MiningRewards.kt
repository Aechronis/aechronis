package net.aechronis.economy

import net.aechronis.server.modules.ModuleEvents
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.PlayerBreakListener
import net.aechronis.vanilla.managers.BlockDrops
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.item.component.EnchantmentList
import net.minestom.server.item.enchant.Enchantment

object MiningRewards {
    // Run after block interactions (0), before Vanilla's ordinary drops and tool damage (2).
    private val eventNode =
        EventNode.all("economy-mining-rewards").setPriority(1).addListener(PlayerBlockBreakEvent::class.java, ::onBlockBreak)
    private lateinit var config: EconomyConfig

    fun init(config: EconomyConfig = EconomyConfig()) {
        this.config = config
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), eventNode)
    }

    private fun onBlockBreak(event: PlayerBlockBreakEvent) {
        if (!Vanilla.config.blockDropsEnabled || !PlayerBreakListener.canBreak(event)) return
        val player = event.player
        val instance = player.instance ?: return
        val material = event.block.registry()?.material() ?: return
        val blocks = Vanilla.config.blocksConfig
        val heldItem = player.itemInMainHand
        val hasSilkTouch = heldItem.get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY).has(Enchantment.SILK_TOUCH)
        val silkTouch = hasSilkTouch && material in blocks.blocksSilkTouchable
        val amount = if (silkTouch) null else config.coinBlocks[material]
        if (amount != null) {
            val balance = Economy.addCoins(player, amount.toLong())
            BlockDrops.suppress(event)
            player.sendActionBar(
                Component
                    .text("+$amount coin${if (amount == 1) "" else "s"} ", NamedTextColor.GOLD)
                    .append(Component.text("(balance: $balance)", NamedTextColor.GRAY)),
            )
            return
        }

        if (material.name().endsWith("_ore") || material == Material.ANCIENT_DEBRIS) {
            BlockDrops.suppress(event)
            val drops =
                if (silkTouch) {
                    listOf(ItemStack.of(material))
                } else {
                    blocks.blockDrops[material] ?: listOf(ItemStack.of(material))
                }
            for (stack in drops) {
                if (!stack.isAir && stack.amount() > 0) BlockDrops.giveOrDrop(player, instance, stack)
            }
        }
    }

    fun shutdown() {
        MinecraftServer.getGlobalEventHandler().removeChild(eventNode)
    }
}
