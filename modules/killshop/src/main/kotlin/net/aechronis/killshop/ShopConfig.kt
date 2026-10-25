package net.aechronis.killshop

import net.aechronis.killshop.objects.ShopItem

data class ShopConfig(
    val enabled: Boolean = true,
    val shopItems: List<ShopItem> = listOf(),
)
