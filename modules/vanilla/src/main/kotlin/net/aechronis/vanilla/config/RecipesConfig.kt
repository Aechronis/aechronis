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
            Shaped(
                3,
                1,
                arrayOf(
                    RecipesIngredient.of(Material.WHEAT)!!,
                    RecipesIngredient.of(Material.WHEAT)!!,
                    RecipesIngredient.of(Material.WHEAT)!!,
                ),
                ItemStack.of(Material.BREAD),
            ),
            Shaped(
                3,
                3,
                arrayOf(
                    RecipesIngredient.of(Material.OAK_PLANKS)!!,
                    RecipesIngredient.of(Material.OAK_SLAB)!!,
                    RecipesIngredient.of(Material.OAK_PLANKS)!!,
                    RecipesIngredient.of(Material.OAK_PLANKS)!!,
                    null,
                    RecipesIngredient.of(Material.OAK_PLANKS)!!,
                    RecipesIngredient.of(Material.OAK_PLANKS)!!,
                    RecipesIngredient.of(Material.OAK_SLAB)!!,
                    RecipesIngredient.of(Material.OAK_PLANKS)!!,
                ),
                ItemStack.of(Material.BARREL),
            ),
            RecipesShapeless(
                listOf(RecipesIngredient.of(Material.OAK_LOG)!!),
                ItemStack.of(Material.OAK_PLANKS, 4),
            ),
        ),
)
