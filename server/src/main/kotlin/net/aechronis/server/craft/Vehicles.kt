package net.aechronis.server.craft

import net.aechronis.server.constants.Cars
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.item.Material

object Vehicles {
    // This is the scout drone recipe with one fewer diamond, making the truck slightly cheaper.
    val list: List<Recipe> =
        listOf(
            Shaped(
                3,
                3,
                arrayOf(
                    RecipesIngredient.of(Material.DIAMOND)!!,
                    RecipesIngredient.of(Material.COPPER_INGOT)!!,
                    RecipesIngredient.of(Material.DIAMOND)!!,
                    RecipesIngredient.of(Material.REDSTONE)!!,
                    RecipesIngredient.of(Material.COAL)!!,
                    RecipesIngredient.of(Material.REDSTONE)!!,
                    RecipesIngredient.of(Material.DIAMOND)!!,
                    RecipesIngredient.of(Material.COPPER_INGOT)!!,
                    null,
                ),
                Cars.humvee.toItemStack(),
            ),
        )
}
