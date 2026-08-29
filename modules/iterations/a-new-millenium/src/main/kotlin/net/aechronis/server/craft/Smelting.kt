package net.aechronis.server.craft

import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object Smelting {
    private val smeltables =
        listOf(
            Material.COAL_ORE to Material.COAL,
            Material.DEEPSLATE_COAL_ORE to Material.COAL,
            Material.IRON_ORE to Material.IRON_INGOT,
            Material.DEEPSLATE_IRON_ORE to Material.IRON_INGOT,
            Material.RAW_IRON to Material.IRON_INGOT,
            Material.COPPER_ORE to Material.COPPER_INGOT,
            Material.DEEPSLATE_COPPER_ORE to Material.COPPER_INGOT,
            Material.RAW_COPPER to Material.COPPER_INGOT,
            Material.GOLD_ORE to Material.GOLD_INGOT,
            Material.DEEPSLATE_GOLD_ORE to Material.GOLD_INGOT,
            Material.NETHER_GOLD_ORE to Material.GOLD_INGOT,
            Material.RAW_GOLD to Material.GOLD_INGOT,
            Material.REDSTONE_ORE to Material.REDSTONE,
            Material.DEEPSLATE_REDSTONE_ORE to Material.REDSTONE,
            Material.LAPIS_ORE to Material.LAPIS_LAZULI,
            Material.DEEPSLATE_LAPIS_ORE to Material.LAPIS_LAZULI,
            Material.DIAMOND_ORE to Material.DIAMOND,
            Material.DEEPSLATE_DIAMOND_ORE to Material.DIAMOND,
            Material.EMERALD_ORE to Material.EMERALD,
            Material.DEEPSLATE_EMERALD_ORE to Material.EMERALD,
            Material.NETHER_QUARTZ_ORE to Material.QUARTZ,
            Material.ANCIENT_DEBRIS to Material.NETHERITE_SCRAP,
            Material.BEEF to Material.COOKED_BEEF,
            Material.CHICKEN to Material.COOKED_CHICKEN,
            Material.COD to Material.COOKED_COD,
            Material.MUTTON to Material.COOKED_MUTTON,
            Material.PORKCHOP to Material.COOKED_PORKCHOP,
            Material.RABBIT to Material.COOKED_RABBIT,
            Material.SALMON to Material.COOKED_SALMON,
            Material.POTATO to Material.BAKED_POTATO,
            Material.KELP to Material.DRIED_KELP,
            Material.SAND to Material.GLASS,
        )

    val list: List<Recipe> =
        smeltables.map { (input, output) ->
            Shaped(
                3,
                3,
                Array(9) { slot ->
                    RecipesIngredient.of(if (slot == 4) Material.COAL else input)
                },
                ItemStack.of(output, 8),
            )
        }
}
