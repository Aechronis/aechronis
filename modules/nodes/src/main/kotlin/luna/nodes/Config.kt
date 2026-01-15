/**
 * NodesConfig
 *
 * Configuration data class for the library.
 * All fields have defaults - only need to specify what needs to change.
 */

package luna.nodes

import luna.nodes.objects.TerritoryResources
import net.minestom.server.instance.block.Block
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

data class NodesConfig(
    // ===================================
    // engine configs
    // ===================================
    // disable saving (for tests)
    val save: Boolean = true,

    // main plugin path for config and saves
    val pathPlugin: String = "nodes",

    // period for running world save
    val savePeriod: Int = 600,

    // all long tick cycle values
    // 1 hour = 3600000 ms
    // 2 hour = 7200000 ms
    val backupPeriod: Long = 3600000L, // 1 hour

    // main tick period check for backup, income, town + resident cooldown counters
    val mainPeriodicTick: Long = 1200L,

    // nametag update period
    val nametagPipelineTicks: Int = 16,

    // ===================================
    // resource configs
    // ===================================
    // territory do income enabled and income time
    val incomeEnabled: Boolean = true,
    val incomePeriod: Long = 3600000L,

    // global resource node in all territories
    val globalResources: TerritoryResources = TerritoryResources(),

    // hidden ore blocks, stone only
    val oreBlocks: Set<Block> = setOf(
        Block.STONE, // note: granite, diorite, and andesite are variants of stone in 1.12.2
        Block.GRANITE,
        Block.ANDESITE,
        Block.DIORITE,
    ),

    // allow mining in unowned territories
    val allowOreInWilderness: Boolean = false,

    // allow getting ore in captured territory
    val allowOreInCaptured: Boolean = true,

    // allow mining in other towns in nation
    val allowOreInNationTowns: Boolean = true,

    // ===================================
    // permissions
    // ===================================
    // interact in area with NO TERRITORIES (build, destroy, etc...)
    val canInteractInEmpty: Boolean = false,

    // interact in territory without town (build, destroy, etc...)
    val canInteractInUnclaimed: Boolean = true,

    // ===================================
    // town cooldowns
    // ===================================
    // 24 hour = 86400000 ms
    // 48 hour = 172800000 ms
    // 72 hour = 259200000 ms
    val townCreateCooldown: Long = 172800000L,

    val townMoveHomeCooldown: Long = 172800000L,

    // annexation settings
    // only allow annexing during war time
    val canOnlyAnnexDuringWar: Boolean = true,

    // ===================================
    // town settings
    // ===================================
    // town spawn timer in seconds (converted to ticks by num * 20)
    val townSpawnTime: Int = 10,

    // ===================================
    // nation settings
    // ===================================
    // allow members of the same nation to attack eachother
    val allowNationFriendlyFire: Boolean = false,

    // ===================================
    // alliance settings
    // ===================================
    // allow allied nations to attack eachother
    val allowAllyFriendlyFire: Boolean = true,

    // ===================================
    // captured territory tax rates:
    // (taxation is theft)
    // ===================================
    // fraction of territory income that goes to occupier
    val taxIncomeRate: Double = 0.2,

    // probability that a hidden ore event resources go to occupier
    val taxMineRate: Double = 0.2,

    // ===================================
    // war configs
    // ===================================
    // Nodes internal explosion block damage restrictions
    val restrictExplosions: Boolean = true,
    val onlyAllowExplosionsDuringWar: Boolean = true,

    val flagBlockDefault: Block = Block.OAK_FENCE,

    val flagBlocks: Set<Block> = setOf(
        Block.ACACIA_FENCE,
        Block.BIRCH_FENCE,
        Block.BAMBOO_FENCE,
        Block.CHERRY_FENCE,
        Block.DARK_OAK_FENCE,
        Block.JUNGLE_FENCE,
        Block.MANGROVE_FENCE,
        Block.OAK_FENCE,
        Block.SPRUCE_FENCE,
    ),

    // disable building within this distance of flag (square range)
    val flagNoBuildDistance: Int = 1,

    // disable building for y > flag base block + flagNoBuildYOffset
    val flagNoBuildYOffset: Int = -1,

    // ticks required to capture chunk
    val chunkAttackTime: Long = 200,

    // multiplier for chunk attacks
    val chunkAttackFromWastelandMultiplier: Double = 2.0, // territory next to wilderness
    val chunkAttackHomeMultiplier: Double = 2.0, // in home territory

    // number of chunks a player can attack at same time
    val maxPlayerChunkAttacks: Int = 1,

    // allow breaking allies war flags
    val allowBreakingAlliesFlags: Boolean = false,

    // flag sky beacon config
    val flagBeaconSize: Int = 6, // be in range [2, 16]
    val flagBeaconMinSkyLevel: Int = 100, // minimum height level in sky
    val flagBeaconSkyLevel: Int = 50, // height level above blocks

    // allow war permissions during skirmish mode
    val allowDestructionDuringSkirmish: Boolean = false,

    // bypass permissions and allow extended ally interactions in towns
    val warPermissions: Boolean = true,

    // allow leaving towns/natiosn during war
    val canLeaveTownDuringWar: Boolean = true,

    // allow creating towns, nation stuff during war
    val canCreateTownDuringWar: Boolean = false,
    val canDestroyTownDuringWar: Boolean = false,
    val canLeaveNationDuringWar: Boolean = false,

    // global disable annexing
    val annexDisabled: Boolean = false,

    // war whitelist: only allow attacking these town UUIDs
    val warWhitelist: Set<UUID> = emptySet(),

    // war blacklist: disable attacking these town UUIDs
    val warBlacklist: Set<UUID> = emptySet(),

    // annex blacklist: cannot annex these towns, only occupy
    val annexBlacklist: Set<UUID> = emptySet(),

    // whitelist settings

    // only towns in whitelist can annex territories (from other whitelist territories)
    val onlyWhitelistCanAnnex: Boolean = true,
    val onlyWhitelistCanClaim: Boolean = true,

    // multiplier for warping home when occupied
    val occupiedHomeTeleportMultiplier: Int = 12,

    // List of town UUIDs to allow building in occupied territory.
    // War whitelist often used to create AI towns that can be attacked
    // by anyone. People want to build in occupied territory from these
    // towns during non-war time. This list allows building/interacting
    // in these occupied towns.
    val allowControlInOccupiedTownList: Set<UUID> = emptySet(),

    // ===================================
    // port configs
    // ===================================
    val seaLevel: Double = 62.0,
    val portWarpTime: Double = 5.0 * 20.0, // in ticks
    val allowPortWarpWithoutBoat: Boolean = false,
) {
    // folder for backups of json state files
    val pathBackup: Path get() = Paths.get(pathPlugin, "backup").normalize()

    // file names for world, towns, war json files
    val pathWorld: Path get() = Paths.get(pathPlugin, "world.json").normalize()
    val pathTowns: Path get() = Paths.get(pathPlugin, "towns.json").normalize()
    val pathWar: Path get() = Paths.get(pathPlugin, "war.json").normalize()
    val pathPorts: Path get() = Paths.get(pathPlugin, "ports.json").normalize()
    val pathLastBackupTime: Path get() = Paths.get(pathPlugin, "lastBackupTime.txt").normalize()
    val pathLastIncomeTime: Path get() = Paths.get(pathPlugin, "lastIncomeTime.txt").normalize()

    // use whitelist/blacklist for war (derived from list.size > 0 for lists below)
    val warUseWhitelist: Boolean get() = warWhitelist.isNotEmpty()
    val warUseBlacklist: Boolean get() = warBlacklist.isNotEmpty()
    val useAnnexBlacklist: Boolean get() = annexBlacklist.isNotEmpty()
}
