package net.aechronis.killshop.objects

import net.minestom.server.item.ItemStack

data class ShopItem(
    val itemStack: ItemStack,
    val cooldownTicks: Long,
    val cost: Int,
    /** Chest slot to show the item in; defaults to its position in the list. */
    val slot: Int? = null,
)
