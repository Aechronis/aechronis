package net.aechronis.server.craft

import net.aechronis.combat.objects.ArmorPiece
import net.aechronis.server.constants.Ammo
import net.aechronis.server.constants.Armor
import net.aechronis.server.constants.Drones
import net.aechronis.server.constants.Guns
import net.aechronis.server.constants.Melees
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.RecipesShapeless
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object Weapons {
    val list: List<Recipe> =
        buildList {
            add(
                shapeless(
                    ItemStack.of(Material.GUNPOWDER, 2),
                    Material.COAL_BLOCK to 1,
                    Material.REDSTONE to 2,
                    Material.BLAZE_POWDER to 2,
                ),
            )
            add(
                shapeless(
                    ItemStack.of(Material.BREAD, 3),
                    Material.WHEAT to 3,
                ),
            )
            add(shapeless(Ammo.ammo762x39mm.toItemStack().withAmount(8), Material.COPPER_INGOT to 3, Material.GUNPOWDER to 2))
            add(shapeless(Ammo.ammo9mm.toItemStack().withAmount(4), Material.COPPER_INGOT to 2, Material.GUNPOWDER to 1))
            add(
                shapeless(
                    Ammo.ammo762x39mmExplosive.toItemStack().withAmount(4),
                    Material.COPPER_INGOT to 3,
                    Material.GUNPOWDER to 4,
                ),
            )
            add(
                shapeless(
                    Ammo.tankShell.toItemStack().withAmount(4),
                    Material.COPPER_INGOT to 4,
                    Material.IRON_BLOCK to 1,
                    Material.GUNPOWDER to 4,
                ),
            )

            // Uniform recipes use dyes to choose their faction's equipment asset.
            addAll(uniformRecipes(Armor.usMarineJacket, Armor.usMarineTrousers, Armor.usMarineBoots, Material.YELLOW_DYE))
            addAll(uniformRecipes(Armor.idfJacket, Armor.idfTrousers, Armor.idfBoots, Material.GREEN_DYE))
            addAll(uniformRecipes(Armor.chineseArmyJacket, Armor.chineseArmyTrousers, Armor.chineseArmyBoots, Material.RED_DYE))
            addAll(uniformRecipes(Armor.iranJacket, Armor.iranTrousers, Armor.iranBoots, Material.WHITE_DYE))
            addAll(uniformRecipes(Armor.iraqJacket, Armor.iraqTrousers, Armor.iraqBoots, Material.BLACK_DYE))
            addAll(uniformRecipes(Armor.kurdJacket, Armor.kurdTrousers, Armor.kurdBoots, Material.BLUE_DYE))
            addAll(
                uniformRecipes(
                    Armor.lebanonInsurgentJacket,
                    Armor.lebanonInsurgentTrousers,
                    Armor.lebanonInsurgentBoots,
                    Material.ORANGE_DYE,
                ),
            )
            addAll(
                uniformRecipes(
                    Armor.palestineInsurgentJacket,
                    Armor.palestineInsurgentTrousers,
                    Armor.palestineInsurgentBoots,
                    Material.LIGHT_BLUE_DYE,
                ),
            )
            addAll(uniformRecipes(Armor.russiaArmyJacket, Armor.russiaArmyTrousers, Armor.russiaArmyBoots, Material.LIGHT_GRAY_DYE))
            addAll(uniformRecipes(Armor.syriaJacket, Armor.syriaTrousers, Armor.syriaBoots, Material.BROWN_DYE))
            addAll(uniformRecipes(Armor.turkeyJacket, Armor.turkeyTrousers, Armor.turkeyBoots, Material.PURPLE_DYE))

            // G = gold block, D = diamond, B = diamond block, I = iron block, i = iron ingot
            add(shapedRecipe(Melees.baton.toItemStack(), " i ", " ii", " i "))
            add(shapedRecipe(Guns.m4a1.toEmptyItemStack(), "DDD", "DGD", "DID"))
            add(shapedRecipe(Guns.ak12.toEmptyItemStack(), "DDD", "DGI"))
            add(shapedRecipe(Guns.qbz95.toEmptyItemStack(), "DDD", "DGI", "D  "))
            add(shapedRecipe(Guns.ak74.toEmptyItemStack(), "DGD", " I ", " D "))
            add(shapedRecipe(Guns.g3.toEmptyItemStack(), "GD", "DI"))
            add(shapedRecipe(Guns.awp.toEmptyItemStack(), "BBG", "BII", "BGB"))
            add(shapedRecipe(Guns.glock17.toEmptyItemStack(), "GDI"))
            add(shapedRecipe(Guns.m9.toEmptyItemStack(), "G", "D", "I"))
            add(shapedRecipe(Guns.mp5.toEmptyItemStack(), "GD", "ID"))
            add(shapedRecipe(Guns.vz61.toEmptyItemStack(), "GD", "I "))
            add(shapedRecipe(Guns.mg3.toEmptyItemStack(), "DDD", "DGD", "DI "))
            add(shapedRecipe(Guns.at4.toItemStack(), "GBI", "IBG"))

            // C = copper ingot, R = redstone, O = coal, P = gunpowder
            add(shapedRecipe(Drones.scoutDrone.toItemStack(), "DCD", "ROR", "DCD"))
            add(shapedRecipe(Drones.kamikazeDrone.toItemStack(), "BIB", "RPR", "BIB"))
        }

    private fun uniformRecipes(
        jacket: ArmorPiece,
        trousers: ArmorPiece,
        boots: ArmorPiece,
        dye: Material,
    ): List<Recipe> =
        listOf(
            shapeless(
                jacket.toItemStack(),
                Material.GOLD_INGOT to 2,
                Material.DIAMOND to 2,
                Material.IRON_BLOCK to 1,
                dye to 1,
            ),
            shapeless(
                trousers.toItemStack(),
                Material.GOLD_INGOT to 2,
                Material.DIAMOND to 2,
                Material.IRON_INGOT to 4,
                dye to 1,
            ),
            shapeless(
                boots.toItemStack(),
                Material.GOLD_INGOT to 1,
                Material.DIAMOND to 1,
                Material.IRON_INGOT to 2,
                dye to 1,
            ),
        )

    private fun shapeless(
        output: ItemStack,
        vararg materialCounts: Pair<Material, Int>,
    ): RecipesShapeless = RecipesShapeless(ingredients(*materialCounts), output)

    private fun shapedRecipe(
        output: ItemStack,
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
                            'G' -> RecipesIngredient.of(Material.GOLD_BLOCK)!!
                            'D' -> RecipesIngredient.of(Material.DIAMOND)!!
                            'B' -> RecipesIngredient.of(Material.DIAMOND_BLOCK)!!
                            'I' -> RecipesIngredient.of(Material.IRON_BLOCK)!!
                            'i' -> RecipesIngredient.of(Material.IRON_INGOT)!!
                            'C' -> RecipesIngredient.of(Material.COPPER_INGOT)!!
                            'R' -> RecipesIngredient.of(Material.REDSTONE)!!
                            'O' -> RecipesIngredient.of(Material.COAL)!!
                            'P' -> RecipesIngredient.of(Material.GUNPOWDER)!!
                            ' ' -> null
                            else -> error("Unknown shaped recipe symbol: $symbol")
                        }
                    }
                }.toTypedArray()

        return Shaped(width, rows.size, pattern, output)
    }

    private fun ingredients(vararg materialCounts: Pair<Material, Int>): List<RecipesIngredient> =
        buildList {
            for ((material, count) in materialCounts) {
                require(count > 0) { "Recipe ingredient count must be positive" }
                repeat(count) { add(RecipesIngredient.of(material)!!) }
            }
        }
}
