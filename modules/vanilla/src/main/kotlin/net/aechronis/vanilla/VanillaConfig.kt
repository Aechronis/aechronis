package net.aechronis.vanilla

import net.aechronis.vanilla.config.BlocksConfig
import net.aechronis.vanilla.config.FoodConfig
import net.aechronis.vanilla.config.RecipesConfig
import net.minestom.server.entity.GameMode

data class VanillaConfig(
    // Feature toggles
    val commandsEnabled: Boolean = true,
    val playerDataEnabled: Boolean = true,
    val storageEnabled: Boolean = true,
    val signsEnabled: Boolean = true,
    val shelvesEnabled: Boolean = true,
    val itemFramesEnabled: Boolean = true,
    val whitelistEnabled: Boolean = true,
    val recipesEnabled: Boolean = true,
    val cropsEnabled: Boolean = true,
    val saplingsEnabled: Boolean = true,
    val blocksEnabled: Boolean = true,
    val foodEnabled: Boolean = true,
    val itemsEnabled: Boolean = true,
    val boatsEnabled: Boolean = true,
    val blockDropsEnabled: Boolean = true,
    val efficiencyEnabled: Boolean = true,
    val fallDamageEnabled: Boolean = true,
    val fireDamageEnabled: Boolean = true,
    val drowningEnabled: Boolean = true,
    val voidDamageEnabled: Boolean = true,
    val serverLinksEnabled: Boolean = true,
    val spawnEnabled: Boolean = true,
    val horsesEnabled: Boolean = false,
    // Player defaults
    val defaultGameMode: GameMode = GameMode.SURVIVAL,
    // Paths
    val path: String = "vanilla",
    val playerDataPath: String = "playerdata",
    val whitelistPath: String = "whitelist.json",
    val spawnPath: String = "spawn",
    val votePath: String = "vote.txt",
    val horsesPath: String = "horses.json",
    // Blocks
    val blocksConfig: BlocksConfig = BlocksConfig(),
    // Food
    val foodConfig: FoodConfig = FoodConfig(),
    // Recipes
    val recipesConfig: RecipesConfig = RecipesConfig(),
    // Crops
    val cropGrowthCheckSeconds: Long = 20L,
    val wheatMsPerStage: Long = 72_000L,
    val carrotMsPerStage: Long = 72_000L,
    val potatoMsPerStage: Long = 72_000L,
    // Saplings
    val saplingGrowthMs: Long = 600_000L,
    val saplingGrowthCheckSeconds: Long = 20L,
    val saplingBoneMealAmount: Int = 3,
    // Items (drop & pickup)
    val itemPickupDelayMs: Long = 500L,
    val dropPickupDelayMs: Long = 2_000L,
    val dropDespawnSeconds: Long = 60L,
    val groundItemLimit: Int = 100_000,
    val dropThrowVelocity: Double = 6.0,
    val dropThrowUpwardVelocity: Double = 2.0,
    val dropSpawnHeight: Double = 1.3,
    val dropMagnetRadius: Double = 4.0,
    val dropMagnetSpeed: Double = 3.0,
    // Server Links
    val serverLinks: List<Pair<String, String>> =
        listOf(
            "Map" to "https://map.aechronis.net",
            "Website" to "https://aechronis.net",
            "Discord" to "https://discord.gg/aechronis",
            "Store" to "https://shop.aechronis.net",
        ),
    // Horses
    val horseMountCooldownMs: Long = 3_000L,
    // EnviromentalDmg
    val maxAirTicks: Int = 300,
    val fireTicks: Int = 160,
    val fireContactTicks: Int = 10,
    val fireDmg: Float = 1f,
    val drowningDmg: Float = 2f,
    val voidDamageY: Double = -80.0,
    val voidDamageTicks: Int = 10,
    val voidDmg: Float = 4f,
)
