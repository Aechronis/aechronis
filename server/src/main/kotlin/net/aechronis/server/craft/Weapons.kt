package net.aechronis.server.craft

import net.aechronis.combat.objects.Hat
import net.aechronis.combat.storage.HatCollection
import net.aechronis.server.constants.Ammo
import net.aechronis.server.constants.Armor
import net.aechronis.server.constants.Guns
import net.aechronis.server.constants.Hats
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesGrid
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.RecipesResult
import net.aechronis.vanilla.objects.RecipesShapeless
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

/** Crafting recipes for player weapons, ammunition, uniforms, and the gas-mask cosmetic. */
object Weapons {
    val list: List<Recipe> =
        buildList {
            add(
                shapeless(
                    ItemStack.of(Material.GUNPOWDER, 6),
                    Material.COAL to 1,
                    Material.SULFUR to 2,
                    Material.BLAZE_POWDER to 2,
                ),
            )

            add(shapeless(Ammo.ammo762x39mm.toItemStack().withAmount(4), Material.COPPER_INGOT to 3, Material.GUNPOWDER to 2))
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
                    Ammo.rocket.toItemStack().withAmount(4),
                    Material.COPPER_INGOT to 3,
                    Material.IRON_BLOCK to 1,
                    Material.GUNPOWDER to 5,
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
            add(
                hatUnlockRecipe(
                    Hats.gasMask,
                    Material.GOLD_INGOT to 1,
                    Material.DIAMOND to 1,
                    Material.IRON_INGOT to 3,
                    Material.BLACK_DYE to 1,
                ),
            )
            add(
                shapeless(
                    Armor.usMarineJacket.toItemStack(),
                    Material.GOLD_INGOT to 2,
                    Material.DIAMOND to 2,
                    Material.IRON_BLOCK to 1,
                    Material.YELLOW_DYE to 1,
                ),
            )
            add(
                shapeless(
                    Armor.usMarineTrousers.toItemStack(),
                    Material.GOLD_INGOT to 2,
                    Material.DIAMOND to 2,
                    Material.IRON_INGOT to 4,
                    Material.YELLOW_DYE to 1,
                ),
            )
            add(
                shapeless(
                    Armor.usMarineBoots.toItemStack(),
                    Material.GOLD_INGOT to 1,
                    Material.DIAMOND to 1,
                    Material.IRON_INGOT to 2,
                    Material.YELLOW_DYE to 1,
                ),
            )
            add(
                shapeless(
                    Armor.idfJacket.toItemStack(),
                    Material.GOLD_INGOT to 2,
                    Material.DIAMOND to 2,
                    Material.IRON_BLOCK to 1,
                    Material.GREEN_DYE to 1,
                ),
            )
            add(
                shapeless(
                    Armor.idfTrousers.toItemStack(),
                    Material.GOLD_INGOT to 2,
                    Material.DIAMOND to 2,
                    Material.IRON_INGOT to 4,
                    Material.GREEN_DYE to 1,
                ),
            )
            add(
                shapeless(
                    Armor.idfBoots.toItemStack(),
                    Material.GOLD_INGOT to 1,
                    Material.DIAMOND to 1,
                    Material.IRON_INGOT to 2,
                    Material.GREEN_DYE to 1,
                ),
            )

            add(shapeless(Guns.m4a1.toEmptyItemStack(), Material.GOLD_BLOCK to 1, Material.DIAMOND to 7, Material.IRON_BLOCK to 1))
            add(shapeless(Guns.ak12.toEmptyItemStack(), Material.GOLD_BLOCK to 1, Material.DIAMOND to 4, Material.IRON_BLOCK to 1))
            add(shapeless(Guns.qbz95.toEmptyItemStack(), Material.GOLD_BLOCK to 1, Material.DIAMOND to 5, Material.IRON_BLOCK to 1))
            add(shapeless(Guns.ak74.toEmptyItemStack(), Material.GOLD_BLOCK to 1, Material.DIAMOND to 3, Material.IRON_BLOCK to 1))
            add(shapeless(Guns.g3.toEmptyItemStack(), Material.GOLD_BLOCK to 1, Material.DIAMOND to 2, Material.IRON_BLOCK to 1))
            add(shapeless(Guns.glock17.toEmptyItemStack(), Material.GOLD_BLOCK to 1, Material.DIAMOND to 1, Material.IRON_BLOCK to 1))
            add(shapeless(Guns.m9.toEmptyItemStack(), Material.GOLD_BLOCK to 1, Material.DIAMOND to 1, Material.IRON_BLOCK to 1))
            add(shapeless(Guns.mp5.toEmptyItemStack(), Material.GOLD_BLOCK to 1, Material.DIAMOND to 2, Material.IRON_BLOCK to 1))
            add(shapeless(Guns.vz61.toEmptyItemStack(), Material.GOLD_BLOCK to 1, Material.DIAMOND to 1, Material.IRON_BLOCK to 1))
            add(shapeless(Guns.mg3.toEmptyItemStack(), Material.GOLD_BLOCK to 1, Material.DIAMOND to 6, Material.IRON_BLOCK to 1))
            add(shapeless(Guns.at4.toEmptyItemStack(), Material.GOLD_BLOCK to 2, Material.DIAMOND_BLOCK to 1, Material.IRON_BLOCK to 2))
        }

    private fun shapeless(
        output: ItemStack,
        vararg materialCounts: Pair<Material, Int>,
    ): RecipesShapeless = RecipesShapeless(ingredients(*materialCounts), output)

    private fun hatUnlockRecipe(
        hat: Hat,
        vararg materialCounts: Pair<Material, Int>,
    ): Recipe = HatUnlockRecipe(hat, ingredients(*materialCounts))

    private fun ingredients(vararg materialCounts: Pair<Material, Int>): List<RecipesIngredient> =
        buildList {
            for ((material, count) in materialCounts) {
                require(count > 0) { "Recipe ingredient count must be positive" }
                repeat(count) { add(RecipesIngredient.of(material)!!) }
            }
        }

    private class HatUnlockRecipe(
        private val hat: Hat,
        ingredients: List<RecipesIngredient>,
    ) : Recipe {
        private val delegate = RecipesShapeless(ingredients, hat.toItemStack())

        override fun match(recipesGrid: RecipesGrid): RecipesResult? = delegate.match(recipesGrid)?.copy(recipe = this)

        override fun canCraft(player: Player): Boolean = !HatCollection.owns(player.uuid, hat)

        override fun grantsItem(): Boolean = false

        override fun onCraft(player: Player) {
            HatCollection.give(player.uuid, hat)
        }
    }
}
