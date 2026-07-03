package net.aechronis.vanilla

import net.aechronis.vanilla.objects.FoodItem
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.RecipesShapeless
import net.aechronis.vanilla.objects.Shaped
import net.aechronis.vanilla.objects.ShopItem
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

data class VanillaConfig(
    // Paths
    val path: String = "vanilla",
    val playerDataPath: String = "playerdata",
    val storagePath: String = "storage",
    val whitelistPath: String = "whitelist.json",
    // Crops
    val cropGrowthCheckSeconds: Long = 20L,
    val wheatMsPerStage: Long = 72_000L,
    val carrotMsPerStage: Long = 72_000L,
    val potatoMsPerStage: Long = 72_000L,
    // Saplings
    val saplingGrowthMs: Long = 600_000L,
    val saplingGrowthCheckSeconds: Long = 20L,
    val saplingBoneMealAmount: Int = 3,
    // Mannequins
    val mannequinDespawnTime: Int = 60,
    // Items (drop & pickup)
    val dropPickupDelayMs: Long = 2_000L,
    val dropDespawnSeconds: Long = 300L,
    val dropThrowVelocity: Double = 6.0,
    val dropThrowUpwardVelocity: Double = 2.0,
    val dropSpawnHeight: Double = 1.3,
    val dropMagnetRadius: Double = 4.0,
    val dropMagnetSpeed: Double = 3.0,
    // Elevator
    val elevatorMaxSearch: Int = 120,
    // Blocks
    val blocksStoneType: List<Material> =
        listOf(
            Material.STONE,
            Material.COBBLESTONE,
        ),
    val blocksGrassType: List<Material> =
        listOf(
            Material.DIRT,
            Material.GRASS_BLOCK,
            Material.COARSE_DIRT,
            Material.PODZOL,
        ),
    val blocksWoodType: List<Material> =
        listOf(
            Material.OAK_PLANKS,
            Material.SPRUCE_PLANKS,
            Material.BIRCH_PLANKS,
            Material.JUNGLE_PLANKS,
            Material.ACACIA_PLANKS,
            Material.DARK_OAK_PLANKS,
        ),
    // TreeFeller
    val treeFellerMaxSize: Int = 120,
    val treeFellerMaxHeight: Int = 26,
    val treeFellerBreakLeaves: Boolean = true,
    val treeFellerLeafMaxDistance: Int = 6,
    val treeFellerMaxLeaves: Int = 600,
    val treeFellerBlocksPerTick: Int = 8,
    val treeFellerTickInterval: Int = 1,
    val treeFellerSaplingChance: Double = 0.05,
    val treeFellerStickChance: Double = 0.02,
    // Food
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
    // Shop
    val shopItems: List<ShopItem> = listOf(),
    // Recipes
    val recpies: List<Recipe> =
        listOf(
            Shaped(
                2,
                2,
                arrayOf(
                    RecipesIngredient.of(Material.OAK_PLANKS)!!,
                    RecipesIngredient.of(Material.OAK_PLANKS)!!,
                    RecipesIngredient.of(Material.OAK_PLANKS)!!,
                    RecipesIngredient.of(Material.OAK_PLANKS)!!,
                ),
                ItemStack.of(Material.CRAFTING_TABLE),
            ),
            RecipesShapeless(
                listOf(RecipesIngredient.of(Material.OAK_LOG)!!),
                ItemStack.of(Material.OAK_PLANKS, 4),
            ),
        ),
    // Server Links
    val serverLinks: List<Pair<String, String>> =
        listOf(
            "Website" to "https://example.com",
        ),
)
