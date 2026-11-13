package net.aechronis.server.craft

import net.aechronis.combat.objects.Throwable
import net.aechronis.server.constants.Grenades
import net.aechronis.server.constants.Spears
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.item.Material

/** The grenade is an iron shell around a charge of gunpowder; the spear is an iron tip on a shaft of sticks. */
object ThrowableRecipes {
    // i = iron ingot, P = gunpowder, S = stick
    private val grenade = arrayOf(" i ", "iPi", " i ")
    private val spear = arrayOf("  i", " S ", "S  ")

    val list: List<Recipe> =
        listOf(
            throwableRecipe(Grenades.castIronBall, *grenade),
            throwableRecipe(Spears.spear, *spear),
        )

    private fun throwableRecipe(
        throwable: Throwable,
        vararg rows: String,
    ): Shaped {
        val pattern =
            rows
                .flatMap { row ->
                    row.map { symbol ->
                        when (symbol) {
                            'i' -> RecipesIngredient.of(Material.IRON_INGOT)!!
                            'P' -> RecipesIngredient.of(Material.GUNPOWDER)!!
                            'S' -> RecipesIngredient.of(Material.STICK)!!
                            ' ' -> null
                            else -> error("Unknown shaped recipe symbol: $symbol")
                        }
                    }
                }.toTypedArray()

        return Shaped(rows.first().length, rows.size, pattern, throwable.toItemStack())
    }
}
