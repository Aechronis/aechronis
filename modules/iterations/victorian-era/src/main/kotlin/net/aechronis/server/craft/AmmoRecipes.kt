package net.aechronis.server.craft

import net.aechronis.combat.objects.Ammo
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.RecipesShapeless
import net.minestom.server.item.Material
import net.aechronis.server.constants.Ammo as Ammunition

/** Small-arms rounds are iron and gunpowder; field piece ammo is bought in bulk with iron blocks. */
object AmmoRecipes {
    val list: List<Recipe> =
        listOf(
            ammoRecipe(Ammunition.revolverCartridge, 8, Material.IRON_INGOT to 1, Material.GUNPOWDER to 1),
            ammoRecipe(Ammunition.rifleCartridge, 8, Material.IRON_INGOT to 2, Material.GUNPOWDER to 1),
            ammoRecipe(Ammunition.shotgunShell, 4, Material.IRON_INGOT to 1, Material.GOLD_INGOT to 1, Material.GUNPOWDER to 1),
            ammoRecipe(Ammunition.gatlingFeedCase, 1, Material.IRON_INGOT to 4, Material.GUNPOWDER to 2),
            ammoRecipe(Ammunition.maximAmmunitionBelt, 1, Material.IRON_BLOCK to 1, Material.LEATHER to 1, Material.GUNPOWDER to 4),
            ammoRecipe(Ammunition.artilleryShell, 2, Material.IRON_BLOCK to 1, Material.GUNPOWDER to 4),
        )

    private fun ammoRecipe(
        ammo: Ammo,
        amount: Int,
        vararg materialCounts: Pair<Material, Int>,
    ): RecipesShapeless =
        RecipesShapeless(
            materialCounts.flatMap { (material, count) -> List(count) { RecipesIngredient.of(material)!! } },
            ammo.toItemStack().withAmount(amount),
        )
}
