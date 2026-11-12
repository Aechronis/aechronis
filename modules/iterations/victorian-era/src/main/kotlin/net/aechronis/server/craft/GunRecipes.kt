package net.aechronis.server.craft

import net.aechronis.combat.objects.Gun
import net.aechronis.server.constants.Guns
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.item.Material

/**
 * Guns with the same role and similar stats share a shape; the dye picks the gun within the group.
 * Costs climb from early rifles and revolvers up to line rifles and repeaters.
 */
object GunRecipes {
    // i = iron ingot, I = iron block, g = gold ingot, G = gold block, D = diamond, B = diamond block, P = any planks, L = leather, * = the gun's dye
    private val revolver = arrayOf("iig", " P*")
    private val coachGun = arrayOf("ii ", "iig", "*PP")
    private val earlyRifle = arrayOf(" ii", "*ii", "PP ")
    private val carbine = arrayOf(" ii", "*gi", "PL ")
    private val rifle = arrayOf(" ii", "*gi", "PP ")
    private val lineRifle = arrayOf(" ii", "*Di", "PP ")
    private val repeater = arrayOf(" II", "*BI", "PPG")

    private val planks = RecipesIngredient.of(Material.values().filter { it.key().value().endsWith("_planks") }.toSet())!!

    val list: List<Recipe> =
        listOf(
            // Revolvers: Adams MkIII, Colt 1851 Navy, Colt Single Action Army
            gunRecipe(Guns.adamsMkIII, Material.RED_DYE, *revolver),
            gunRecipe(Guns.colt1851, Material.BLUE_DYE, *revolver),
            gunRecipe(Guns.coltSingleActionArmy, Material.BROWN_DYE, *revolver),
            gunRecipe(Guns.coachGun, Material.BROWN_DYE, *coachGun),
            // Early breech- and muzzle-loaders: slow and less accurate
            gunRecipe(Guns.dreyseNeedleGun, Material.BLACK_DYE, *earlyRifle),
            gunRecipe(Guns.enfieldPattern1853, Material.RED_DYE, *earlyRifle),
            // Mountable carbines, slung with leather
            gunRecipe(Guns.mauserModel1871Jaegerbuechse, Material.GREEN_DYE, *carbine),
            gunRecipe(Guns.chassepotCarbine, Material.BLUE_DYE, *carbine),
            // 192-block rifles
            gunRecipe(Guns.werndlHolub, Material.WHITE_DYE, *rifle),
            gunRecipe(Guns.vetterliModel1870, Material.RED_DYE, *rifle),
            // 224-block line rifles
            gunRecipe(Guns.chassepot, Material.BLUE_DYE, *lineRifle),
            gunRecipe(Guns.martiniHenry, Material.RED_DYE, *lineRifle),
            gunRecipe(Guns.berdanII, Material.GREEN_DYE, *lineRifle),
            gunRecipe(Guns.mauserModel1871, Material.BLACK_DYE, *lineRifle),
            // Lever and magazine repeaters
            gunRecipe(Guns.winchesterModel1873, Material.YELLOW_DYE, *repeater),
            gunRecipe(Guns.spencerModel1860, Material.BLUE_DYE, *repeater),
        )

    private fun gunRecipe(
        gun: Gun,
        dye: Material,
        vararg rows: String,
    ): Shaped {
        require(rows.isNotEmpty() && rows.size <= 3) { "Shaped recipes must be between one and three rows high" }
        val width = rows.first().length
        require(width in 1..3 && rows.all { it.length == width }) {
            "Shaped recipe rows must have the same width between one and three"
        }

        val pattern =
            rows
                .flatMap { row ->
                    row.map { symbol ->
                        when (symbol) {
                            'i' -> RecipesIngredient.of(Material.IRON_INGOT)!!
                            'I' -> RecipesIngredient.of(Material.IRON_BLOCK)!!
                            'g' -> RecipesIngredient.of(Material.GOLD_INGOT)!!
                            'G' -> RecipesIngredient.of(Material.GOLD_BLOCK)!!
                            'D' -> RecipesIngredient.of(Material.DIAMOND)!!
                            'B' -> RecipesIngredient.of(Material.DIAMOND_BLOCK)!!
                            'P' -> planks
                            'L' -> RecipesIngredient.of(Material.LEATHER)!!
                            '*' -> RecipesIngredient.of(dye)!!
                            ' ' -> null
                            else -> error("Unknown shaped recipe symbol: $symbol")
                        }
                    }
                }.toTypedArray()

        return Shaped(width, rows.size, pattern, gun.toEmptyItemStack())
    }
}
