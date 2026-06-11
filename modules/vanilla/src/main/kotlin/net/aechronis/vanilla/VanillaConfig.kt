package net.aechronis.vanilla

import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.RecipesShapeless
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

data class VanillaConfig(
    // Paths
    val path: String = "vanilla",
    val playerDataPath: String = "playerdata",
    val storagePath: String = "storage",
    // Crops
    val cropGrowthCheckSeconds: Long = 20L,
    val wheatMsPerStage: Long = 72_000L,
    val carrotMsPerStage: Long = 72_000L,
    val potatoMsPerStage: Long = 72_000L,
    // Mannequins
    val mannequinDespawnTime: Int = 60,
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
)
