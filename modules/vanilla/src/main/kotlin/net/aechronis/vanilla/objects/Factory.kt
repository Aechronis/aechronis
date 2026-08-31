package net.aechronis.vanilla.objects

import net.kyori.adventure.text.Component
import net.minestom.server.item.ItemStack

data class FactoryRecipe(
    val name: String,
    val displayName: Component,
    val input: List<ItemStack>,
    val output: List<ItemStack>,
)

data class Factory(
    val name: String,
    val itemName: Component,
    val maxTier: Int,
    val recipes: Map<Int, List<FactoryRecipe>>,
)
