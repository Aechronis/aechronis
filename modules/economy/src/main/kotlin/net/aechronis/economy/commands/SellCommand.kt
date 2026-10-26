package net.aechronis.economy.commands

import net.aechronis.economy.Economy
import net.aechronis.economy.constants.SELLABLE_BLOCKS
import net.aechronis.server.CommandOutput
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack

/**
 * Sells plain cobblestone/dirt/log-derived blocks for one coin each.
 * The synchronous executor credits the wallet before removing the counted stacks,
 * so a failed wallet write leaves the player's items intact.
 */
class SellCommand : Command("sell") {
    init {
        setDefaultExecutor { player: Player, _ ->
            var sold = 0
            val slots = arrayListOf<Int>()
            for (slot in 0 until player.inventory.size) {
                val item = player.inventory.getItemStack(slot)
                // Only plain blocks sell; named, enchanted or otherwise customised items are kept.
                if (!item.isAir && item.material() in SELLABLE_BLOCKS && item.isSimilar(ItemStack.of(item.material()))) {
                    sold += item.amount()
                    slots.add(slot)
                }
            }

            if (sold == 0) {
                CommandOutput.send(player, Component.text("You have nothing to sell (cobblestone, dirt, or logs)", NamedTextColor.RED))
                return@setDefaultExecutor
            }

            val balance = Economy.addCoins(player, sold.toLong())
            slots.forEach { player.inventory.setItemStack(it, ItemStack.AIR) }
            val coins = if (sold == 1) "coin" else "coins"
            CommandOutput.send(
                player,
                Component.text("Sold $sold block${if (sold == 1) "" else "s"} for $sold $coins (balance: $balance)", NamedTextColor.AQUA),
            )
        }
    }
}
