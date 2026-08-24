package net.aechronis.vanilla

import net.aechronis.vanilla.config.BlocksConfig
import net.aechronis.vanilla.config.CratesConfig
import net.aechronis.vanilla.config.FoodConfig
import net.aechronis.vanilla.config.MusicConfig
import net.aechronis.vanilla.config.RecipesConfig
import net.aechronis.vanilla.config.ShopConfig

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
    val elevatorEnabled: Boolean = true,
    val mannequinEnabled: Boolean = true,
    val blocksEnabled: Boolean = true,
    val treeFellerEnabled: Boolean = true,
    val foodEnabled: Boolean = true,
    val shopEnabled: Boolean = true,
    val cratesEnabled: Boolean = true,
    val itemsEnabled: Boolean = true,
    val bundlesEnabled: Boolean = true,
    val boatsEnabled: Boolean = true,
    val blockDropsEnabled: Boolean = true,
    val efficiencyEnabled: Boolean = true,
    val fallDamageEnabled: Boolean = true,
    val fireDamageEnabled: Boolean = true,
    val drowningEnabled: Boolean = true,
    val voidDamageEnabled: Boolean = true,
    val serverLinksEnabled: Boolean = true,
    val combatEnabled: Boolean = true,
    val musicEnabled: Boolean = true,
    val spawnEnabled: Boolean = true,
    val kothEnabled: Boolean = true,
    val oresEnabled: Boolean = true,
    // Paths
    val path: String = "vanilla",
    val playerDataPath: String = "playerdata",
    val storagePath: String = "storage",
    val whitelistPath: String = "whitelist.json",
    val spawnPath: String = "spawn",
    val oresPath: String = "ores.json",
    val kothsPath: String = "koth.json",
    val warpsPath: String = "warps.json",
    // Blocks
    val blocksConfig: BlocksConfig = BlocksConfig(),
    // Food
    val foodConfig: FoodConfig = FoodConfig(),
    // Shop
    val shopConfig: ShopConfig = ShopConfig(),
    // Crates
    val cratesConfig: CratesConfig = CratesConfig(),
    // Music
    val musicConfig: MusicConfig = MusicConfig(),
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
    // Mannequins
    val mannequinDespawnTime: Int = 60,
    // Items (drop & pickup)
    val itemPickupDelayMs: Long = 500L,
    val dropPickupDelayMs: Long = 2_000L,
    val dropDespawnSeconds: Long = 300L,
    val dropThrowVelocity: Double = 6.0,
    val dropThrowUpwardVelocity: Double = 2.0,
    val dropSpawnHeight: Double = 1.3,
    val dropMagnetRadius: Double = 4.0,
    val dropMagnetSpeed: Double = 3.0,
    // Bundles
    val bundleMaxItemStacks: Int = 16,
    // Elevator
    val elevatorMaxSearch: Int = 120,
    // TreeFeller
    val treeFellerMaxSize: Int = 300,
    val treeFellerMaxHeight: Int = 50,
    val treeFellerBreakLeaves: Boolean = true,
    val treeFellerLeafMaxDistance: Int = 10,
    val treeFellerMaxLeaves: Int = 1000,
    val treeFellerBlocksPerTick: Int = 8,
    val treeFellerTickInterval: Int = 1,
    val treeFellerSaplingChance: Double = 0.05,
    val treeFellerStickChance: Double = 0.02,
    // Server Links
    val serverLinks: List<Pair<String, String>> =
        listOf(
            "Map" to "https://map.aechronis.net",
            "Website" to "https://aechronis.net",
            "Discord" to "https://discord.gg/aechronis",
            "Store" to "https://shop.aechronis.net",
        ),
    // Warps
    val warpCooldownSeconds: Long = 15L,
    // Combat
    val combatDurationSeconds: Long = 10L,
    val combatTickSeconds: Long = 1L,
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
