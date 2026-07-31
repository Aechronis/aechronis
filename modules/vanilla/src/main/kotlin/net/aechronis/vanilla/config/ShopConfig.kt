package net.aechronis.vanilla.config

import net.aechronis.vanilla.objects.ShopItem

data class ShopConfig(
    val shopItems: List<ShopItem> = listOf(),
)
