package net.aechronis.vanilla.objects

import net.minestom.server.item.ItemStack

data class ShopItem(
    val itemStack: ItemStack,
    val cooldownTicks: Long,
    val cost: Int,
)
