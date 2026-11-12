package net.aechronis.server.craft

import net.aechronis.combat.objects.ArmorPiece
import net.aechronis.server.constants.Armor
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.item.Material

/** Uniforms in the vanilla armour shapes, paid for in ore; the dye in the collar slot picks the nation. */
object UniformRecipes {
    // i = iron ingot, I = iron block, g = gold ingot, D = diamond, * = the nation's dye
    private val jacket = arrayOf("i*i", "gDg", "iIi")
    private val trousers = arrayOf("i*i", "i i", "g g")
    private val boots = arrayOf("i*i", "g g")

    val list: List<Recipe> =
        listOf(
            uniform(Armor.prussiaJacket, Armor.prussiaTrousers, Armor.prussiaBoots, Material.BLACK_DYE),
            uniform(Armor.franceJacket, Armor.franceTrousers, Armor.franceBoots, Material.BLUE_DYE),
            uniform(Armor.britainJacket, Armor.britainTrousers, Armor.britainBoots, Material.RED_DYE),
            uniform(Armor.austriaJacket, Armor.austriaTrousers, Armor.austriaBoots, Material.WHITE_DYE),
            uniform(Armor.russiaJacket, Armor.russiaTrousers, Armor.russiaBoots, Material.GREEN_DYE),
            uniform(Armor.ottomanJacket, Armor.ottomanTrousers, Armor.ottomanBoots, Material.MAGENTA_DYE),
            uniform(Armor.italyJacket, Armor.italyTrousers, Armor.italyBoots, Material.LIGHT_BLUE_DYE),
        ).flatten()

    private fun uniform(
        jacketPiece: ArmorPiece,
        trousersPiece: ArmorPiece,
        bootsPiece: ArmorPiece,
        dye: Material,
    ): List<Recipe> =
        listOf(
            shapedRecipe(jacketPiece, dye, *jacket),
            shapedRecipe(trousersPiece, dye, *trousers),
            shapedRecipe(bootsPiece, dye, *boots),
        )

    private fun shapedRecipe(
        piece: ArmorPiece,
        dye: Material,
        vararg rows: String,
    ): Shaped {
        val pattern =
            rows
                .flatMap { row ->
                    row.map { symbol ->
                        when (symbol) {
                            'i' -> RecipesIngredient.of(Material.IRON_INGOT)!!
                            'I' -> RecipesIngredient.of(Material.IRON_BLOCK)!!
                            'g' -> RecipesIngredient.of(Material.GOLD_INGOT)!!
                            'D' -> RecipesIngredient.of(Material.DIAMOND)!!
                            '*' -> RecipesIngredient.of(dye)!!
                            ' ' -> null
                            else -> error("Unknown shaped recipe symbol: $symbol")
                        }
                    }
                }.toTypedArray()

        return Shaped(rows.first().length, rows.size, pattern, piece.toItemStack())
    }
}
