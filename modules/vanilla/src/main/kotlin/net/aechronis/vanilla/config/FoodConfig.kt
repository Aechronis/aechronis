package net.aechronis.vanilla.config

import net.aechronis.vanilla.objects.FoodItem
import net.minestom.server.item.Material

data class FoodConfig(
    val foodItems: List<FoodItem> =
        listOf(
            FoodItem(Material.APPLE, hunger = 4, saturation = 2.4f),
            FoodItem(Material.BREAD, hunger = 5, saturation = 6.0f),
            FoodItem(Material.BAKED_POTATO, hunger = 5, saturation = 6.0f),
            FoodItem(Material.COOKED_BEEF, hunger = 8, saturation = 12.8f),
            FoodItem(Material.COOKED_CHICKEN, hunger = 6, saturation = 7.2f),
            FoodItem(Material.COOKED_PORKCHOP, hunger = 8, saturation = 12.8f),
            FoodItem(Material.COOKED_COD, hunger = 5, saturation = 6.0f),
            FoodItem(Material.COOKED_SALMON, hunger = 6, saturation = 9.6f),
            FoodItem(Material.COOKED_MUTTON, hunger = 6, saturation = 9.6f),
            FoodItem(Material.COOKED_RABBIT, hunger = 5, saturation = 6.0f),
            FoodItem(Material.CARROT, hunger = 3, saturation = 3.6f),
            FoodItem(Material.POTATO, hunger = 1, saturation = 0.6f),
            FoodItem(Material.MELON_SLICE, hunger = 2, saturation = 1.2f),
            FoodItem(Material.PUMPKIN_PIE, hunger = 8, saturation = 4.8f),
            FoodItem(Material.COOKIE, hunger = 2, saturation = 0.4f),
            FoodItem(Material.GOLDEN_APPLE, hunger = 4, saturation = 9.6f, canAlwaysEat = true),
            FoodItem(Material.BEEF, hunger = 3, saturation = 1.8f),
            FoodItem(Material.CHICKEN, hunger = 2, saturation = 1.2f),
            FoodItem(Material.PORKCHOP, hunger = 3, saturation = 1.8f),
            FoodItem(Material.COD, hunger = 2, saturation = 0.4f),
            FoodItem(Material.SALMON, hunger = 2, saturation = 0.4f),
            FoodItem(Material.MUTTON, hunger = 2, saturation = 1.2f),
            FoodItem(Material.RABBIT, hunger = 3, saturation = 1.8f),
        ),
    val foodTickSeconds: Long = 1L,
    val foodExhaustionPerTick: Float = 0.1f,
    val foodSprintExhaustionMultiplier: Float = 2.0f,
    val foodHealAmount: Float = 1.0f,
    val foodHealSaturationCost: Float = 1.0f,
    val foodStarvationDamage: Float = 1.0f,
)
