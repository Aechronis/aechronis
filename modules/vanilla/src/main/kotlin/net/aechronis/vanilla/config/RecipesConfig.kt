package net.aechronis.vanilla.config

import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.RecipesShapeless
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

data class RecipesConfig(
    val recpies: List<Recipe> =
        listOf(
            Shaped(
                2,
                2,
                arrayOf(
                    RecipesIngredient.of(Material.OAK_PLANKS)!!,
                    RecipesIngredient.of(Material.OAK_PLANKS)!!,
                    RecipesIngredient.of(Material.OAK_PLANKS)!!,
                    RecipesIngredient.of(Material.OAK_PLANKS)!!,
                ),
                ItemStack.of(Material.CRAFTING_TABLE),
            ),
            RecipesShapeless(
                listOf(RecipesIngredient.of(Material.OAK_LOG)!!),
                ItemStack.of(Material.OAK_PLANKS, 4),
            ),
        ),
)
