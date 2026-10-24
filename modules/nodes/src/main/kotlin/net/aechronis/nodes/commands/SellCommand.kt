package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.constants.SELLABLE_BLOCKS
import net.aechronis.nodes.objects.NodesCommand
import net.minestom.server.item.ItemStack

/**
 * Sells every cobblestone/dirt/log(-derived) block in the sender's inventory for 1 stone
 * coin each. Tallies the whole inventory in one synchronous pass and only credits coins
 * after -- see BlockShop.kt's kdoc / the plan doc's anti-dupe section.
 */
class SellCommand : NodesCommand("sell", null) {
    init {
        setDefaultExecutor { player, resident, _ ->
            var sold = 0
            for (slot in 0 until player.inventory.size) {
                val item = player.inventory.getItemStack(slot)
                // Only plain blocks sell; named, enchanted or otherwise customised items are kept.
                if (!item.isAir && item.material() in SELLABLE_BLOCKS && item.isSimilar(ItemStack.of(item.material()))) {
                    sold += item.amount()
                    player.inventory.setItemStack(slot, ItemStack.AIR)
                }
            }

            if (sold == 0) {
                Message.error(player, "You have nothing to sell (cobblestone, dirt, or logs)")
                return@setDefaultExecutor
            }

            resident.addBlockCoins(sold.toLong())
            val coins = if (sold == 1) "stone coin" else "stone coins"
            Message.print(player, "Sold $sold block${if (sold == 1) "" else "s"} for $sold $coins (balance: ${resident.blockCoins})")
        }
    }
}
