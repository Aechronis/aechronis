package net.aechronis.vanilla.objects

import net.kyori.adventure.text.Component
import net.minestom.server.item.ItemStack

data class Factory(
    val name: String,
    val itemName: Component,
    val maxTier: Int,
    val input: Map<Int, List<ItemStack>>,
    val output: Map<Int, List<ItemStack>>,
)
