package net.aechronis.server.craft

import net.aechronis.combat.objects.Melee
import net.aechronis.server.constants.Melees
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.item.Material

/** Swords share an iron blade and brass guard; the dye picks the sword. */
object MeleeRecipes {
    // i = iron ingot, g = gold ingot, S = stick, * = the sword's dye
    private val sword = arrayOf(" i ", "*i ", "gSg")

    val list: List<Recipe> =
        listOf(
            swordRecipe(Melees.cavalrySabre, Material.RED_DYE),
            swordRecipe(Melees.navalCutlass, Material.BLUE_DYE),
        )

    private fun swordRecipe(
        melee: Melee,
        dye: Material,
    ): Shaped {
        val pattern =
            sword
                .flatMap { row ->
                    row.map { symbol ->
                        when (symbol) {
                            'i' -> RecipesIngredient.of(Material.IRON_INGOT)!!
                            'g' -> RecipesIngredient.of(Material.GOLD_INGOT)!!
                            'S' -> RecipesIngredient.of(Material.STICK)!!
                            '*' -> RecipesIngredient.of(dye)!!
                            ' ' -> null
                            else -> error("Unknown shaped recipe symbol: $symbol")
                        }
                    }
                }.toTypedArray()

        return Shaped(sword.first().length, sword.size, pattern, melee.toItemStack())
    }
}
