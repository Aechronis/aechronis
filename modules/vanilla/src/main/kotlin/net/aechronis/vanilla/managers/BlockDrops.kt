package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

/** Where mined blocks send their drops: straight into the inventory for ores, or into the coin balance for stone. */
object BlockDrops {
    /**
     * Set by the module that owns the stone coin balance. Adds the amount for the player and returns the
     * new balance, or null when the player has no balance. Without it, coin blocks drop their items normally.
     */
    @Volatile
    var coinCredit: ((Player, Int) -> Long?)? = null

    fun isOre(material: Material): Boolean = material.name().endsWith("_ore") || material == Material.ANCIENT_DEBRIS

    /** Credits the coins for mining [material] and returns true, or false when it should drop items instead. */
    fun tryCredit(
        player: Player,
        material: Material,
    ): Boolean {
        val amount = Vanilla.config.blocksConfig.coinBlocks[material] ?: return false
        val balance = coinCredit?.invoke(player, amount) ?: return false
        player.sendActionBar(
            Component
                .text("+$amount stone coin${if (amount == 1) "" else "s"} ", NamedTextColor.GOLD)
                .append(Component.text("(balance: $balance)", NamedTextColor.GRAY)),
        )
        return true
    }

    /** Puts [stack] in the inventory, and only falls back to the ground at the player's feet when it is full. */
    fun giveOrDrop(
        player: Player,
        instance: Instance,
        stack: ItemStack,
    ) {
        // Boosted drops can exceed one stack, so hand them over a stack at a time.
        var remaining = stack.amount()
        while (remaining > 0) {
            val part = stack.withAmount(minOf(remaining, stack.maxStackSize()))
            if (!player.inventory.addItemStack(part)) Items.spawn(instance, player.position, part)
            remaining -= part.amount()
        }
    }
}
