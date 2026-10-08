package net.aechronis.server.craft

import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.RecipesShapeless
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

/** Vanilla saddle and golden carrot recipes, plus the gold nuggets the carrot needs. */
object HorseRecipes {
    private val leather = RecipesIngredient.of(Material.LEATHER)!!
    private val ironIngot = RecipesIngredient.of(Material.IRON_INGOT)!!
    private val goldNugget = RecipesIngredient.of(Material.GOLD_NUGGET)!!
    private val carrot = RecipesIngredient.of(Material.CARROT)!!

    val list: List<Recipe> =
        listOf(
            Shaped(
                3,
                2,
                arrayOf(
                    null,
                    leather,
                    null,
                    leather,
                    ironIngot,
                    leather,
                ),
                ItemStack.of(Material.SADDLE),
            ),
            Shaped(
                3,
                3,
                arrayOf(
                    goldNugget,
                    goldNugget,
                    goldNugget,
                    goldNugget,
                    carrot,
                    goldNugget,
                    goldNugget,
                    goldNugget,
                    goldNugget,
                ),
                ItemStack.of(Material.GOLDEN_CARROT),
            ),
            RecipesShapeless(
                listOf(RecipesIngredient.of(Material.GOLD_INGOT)!!),
                ItemStack.of(Material.GOLD_NUGGET, 9),
            ),
            RecipesShapeless(
                List(9) { goldNugget },
                ItemStack.of(Material.GOLD_INGOT),
            ),
        )
}
