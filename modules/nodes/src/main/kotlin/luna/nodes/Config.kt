/**
 * Config
 *
 * Contains global config state variables read in from
 * plugin config.yml file
 */

package luna.nodes

import net.minestom.server.coordinate.Pos
import net.minestom.server.item.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import luna.nodes.objects.OreDeposit
import luna.nodes.objects.TerritoryResources
import net.minestom.server.instance.block.Block
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

object Config {

    // ===================================
    // config
    // ===================================
    // where players spawn
    var spawnLoc = Pos(27000.0, 75.0, 5800.0)

    // folder containing minecraft world
    var pathLevel: Path = Paths.get("world").normalize()

    // spark profiler data folder
    var pathSpark: Path = Paths.get("spark").normalize()

    // ===================================
    // engine configs
    // ===================================
    // main plugin path for config and saves
    var pathPlugin = "nodes"

    // folder for backups of json state files
    var pathBackup: Path = Paths.get("nodes/backup").normalize()

    // file names for world, towns, war json files
    var pathWorld: Path = Paths.get(pathPlugin, "world.json").normalize()
    var pathTowns: Path = Paths.get(pathPlugin, "towns.json").normalize()
    var pathWar: Path = Paths.get(pathPlugin, "war.json").normalize()
    var pathPorts: Path = Paths.get(pathPlugin, "ports.json").normalize()
    var pathLastBackupTime: Path = Paths.get(pathPlugin, "lastBackupTime.txt").normalize()
    var pathLastIncomeTime: Path = Paths.get(pathPlugin, "lastIncomeTime.txt").normalize()

    // period for running world save
    var savePeriod: Int = 600

    // all long tick cycle values
    // 1 hour = 3600000 ms
    // 2 hour = 7200000 ms
    var backupPeriod: Long = 3600000L // 1 hour

    // main tick period check for backup, income, town + resident cooldown counters
    var mainPeriodicTick: Long = 1200L

    // nametag update period
    var nametagPipelineTicks: Int = 16

    // ===================================
    // resource configs
    // ===================================
    // territory do income enabled and income time
    var incomeEnabled: Boolean = true
    var incomePeriod: Long = 3600000L

    // global resource node in all territories
    var globalResources = TerritoryResources()

    // hidden ore blocks, stone only
    var oreBlocks = setOf(
        Block.STONE, // note: granite, diorite, and andesite are variants of stone in 1.12.2
        Block.GRANITE,
        Block.ANDESITE,
        Block.DIORITE,
    )

    // allow mining in unowned territories
    var allowOreInWilderness: Boolean = false

    // allow getting ore in captured territory
    var allowOreInCaptured: Boolean = true

    // allow mining in other towns in nation
    var allowOreInNationTowns: Boolean = true

    // ===================================
    // permissions
    // ===================================
    // interact in area with NO TERRITORIES (build, destroy, etc...)
    var canInteractInEmpty: Boolean = false

    // interact in territory without town (build, destroy, etc...)
    var canInteractInUnclaimed: Boolean = true

    // ===================================
    // town cooldowns
    // ===================================
    // 24 hour = 86400000 ms
    // 48 hour = 172800000 ms
    // 72 hour = 259200000 ms
    var townCreateCooldown: Long = 172800000L

    var townMoveHomeCooldown: Long = 172800000L

    // annexation settings
    // only allow annexing during war time
    var canOnlyAnnexDuringWar: Boolean = true

    // ===================================
    // town settings
    // ===================================
    // town spawn timer in seconds (converted to ticks by num * 20)
    var townSpawnTime: Int = 10

    // ===================================
    // nation settings
    // ===================================
    // allow members of the same nation to attack eachother
    var allowNationFriendlyFire: Boolean = false

    // ===================================
    // alliance settings
    // ===================================
    // allow allied nations to attack eachother
    var allowAllyFriendlyFire: Boolean = true

    // ===================================
    // captured territory tax rates:
    // (taxation is theft)
    // ===================================
    // fraction of territory income that goes to occupier
    var taxIncomeRate: Double = 0.2

    // probability that a hidden ore event resources go to occupier
    var taxMineRate: Double = 0.2

    // ===================================
    // war configs
    // ===================================
    // Nodes internal explosion block damage restrictions
    var restrictExplosions: Boolean = true
    var onlyAllowExplosionsDuringWar: Boolean = true

    var flagBlockDefault: Block = Block.OAK_FENCE

    var flagBlocks: MutableSet<Block> = mutableSetOf(
        Block.ACACIA_FENCE,
        Block.BIRCH_FENCE,
        Block.BAMBOO_FENCE,
        Block.CHERRY_FENCE,
        Block.DARK_OAK_FENCE,
        Block.JUNGLE_FENCE,
        Block.MANGROVE_FENCE,
        Block.OAK_FENCE,
        Block.SPRUCE_FENCE,
    )

    // disable building within this distance of flag (square range)
    var flagNoBuildDistance: Int = 1

    // disable building for y > flag base block + flagNoBuildYOffset
    var flagNoBuildYOffset: Int = -1

    // ticks required to capture chunk
    var chunkAttackTime: Long = 200

    // multiplier for chunk attacks
    var chunkAttackFromWastelandMultiplier: Double = 2.0 // territory next to wilderness
    var chunkAttackHomeMultiplier: Double = 2.0 // in home territory

    // number of chunks a player can attack at same time
    var maxPlayerChunkAttacks: Int = 1

    // allow breaking allies war flags
    var allowBreakingAlliesFlags = false

    // flag sky beacon config
    var flagBeaconSize: Int = 6 // be in range [2, 16]
    var flagBeaconMinSkyLevel: Int = 100 // minimum height level in sky
    var flagBeaconSkyLevel: Int = 50 // height level above blocks

    // allow war permissions during skirmish mode
    var allowDestructionDuringSkirmish: Boolean = false

    // bypass permissions and allow extended ally interactions in towns
    var warPermissions: Boolean = true

    // allow leaving towns/natiosn during war
    var canLeaveTownDuringWar: Boolean = true

    // allow creating towns, nation stuff during war
    var canCreateTownDuringWar: Boolean = false
    var canDestroyTownDuringWar: Boolean = false
    var canLeaveNationDuringWar: Boolean = false

    // global disable annexing
    var annexDisabled: Boolean = false

    // use whitelist/blacklist for war (derived from list.size > 0 for lists below)
    var warUseWhitelist: Boolean = false
    var warUseBlacklist: Boolean = false

    // war whitelist: only allow attacking these town UUIDs
    var warWhitelist: HashSet<UUID> = hashSetOf()

    // war blacklist: disable attacking these town UUIDs
    var warBlacklist: HashSet<UUID> = hashSetOf()

    // annex blacklist: cannot annex these towns, only occupy
    var annexBlacklist: HashSet<UUID> = hashSetOf()
    var useAnnexBlacklist: Boolean = false

    // whitelist settings

    // only towns in whitelist can annex territories (from other whitelist territories)
    var onlyWhitelistCanAnnex: Boolean = true
    var onlyWhitelistCanClaim: Boolean = true

    // multiplier for warping home when occupied
    var occupiedHomeTeleportMultiplier: Int = 12

    // List of town UUIDs to allow building in occupied territory.
    // War whitelist often used to create AI towns that can be attacked
    // by anyone. People want to build in occupied territory from these
    // towns during non-war time. This list allows building/interacting
    // in these occupied towns.
    var allowControlInOccupiedTownList: HashSet<UUID> = hashSetOf()

    // ===================================
    // port configs
    // ===================================
    var seaLevel: Double = 62.0
    var portWarpTime: Double = 5.0 * 20.0 // in ticks
    var allowPortWarpWithoutBoat: Boolean = false

    // ===================================================
    // Load config
    // ===================================================
    fun load(config: FileConfiguration) {
        // paths
        config.getString("pathLevel")?.let { pathLevel = Paths.get(it).normalize() }
        config.getString("pathSpark")?.let { pathSpark = Paths.get(it).normalize() }

        // engine settings
        savePeriod = config.getInt("savePeriod", savePeriod)
        backupPeriod = config.getLong("backupPeriod", backupPeriod)
        mainPeriodicTick = config.getLong("mainPeriodicTick", mainPeriodicTick)
        nametagPipelineTicks = config.getInt("nametagPipelineTicks", nametagPipelineTicks)

        // generic permissions
        canInteractInEmpty = config.getBoolean("canInteractInEmpty", canInteractInEmpty)
        canInteractInUnclaimed = config.getBoolean("canInteractInUnclaimed", canInteractInUnclaimed)

        // town cooldown configs
        townCreateCooldown = config.getLong("townCreateCooldown", townCreateCooldown)
        townMoveHomeCooldown = config.getLong("townMoveHomeCooldown", townMoveHomeCooldown)

        // resource configs
        incomeEnabled = config.getBoolean("incomeEnabled", incomeEnabled)
        incomePeriod = config.getLong("incomePeriod", incomePeriod)
        allowOreInWilderness = config.getBoolean("allowOreInWilderness", allowOreInWilderness)
        allowOreInCaptured = config.getBoolean("allowOreInCaptured", allowOreInCaptured)
        allowOreInNationTowns = config.getBoolean("allowOreInNationTowns", allowOreInNationTowns)

        // global resources in all territories
        val globalResourcesSection = config.getConfigurationSection("globalResources")
        if (globalResourcesSection !== null) {
            globalResources = parseGlobalResources(globalResourcesSection)
        }

        canOnlyAnnexDuringWar = config.getBoolean("canOnlyAnnexDuringWar", canOnlyAnnexDuringWar)

        // town settings
        townSpawnTime = config.getInt("townSpawnTime", townSpawnTime)

        // nation settings
        allowNationFriendlyFire = config.getBoolean("allowNationFriendlyFire", allowNationFriendlyFire)

        // ally settings
        allowAllyFriendlyFire = config.getBoolean("allowAllyFriendlyFire", allowAllyFriendlyFire)

        // tax
        taxIncomeRate = config.getDouble("taxIncomeRate", taxIncomeRate)
        taxMineRate = config.getDouble("taxMineRate", taxMineRate)

        // ======================
        // war
        // ======================
        restrictExplosions = config.getBoolean("restrictExplosions", restrictExplosions)
        onlyAllowExplosionsDuringWar = config.getBoolean("onlyAllowExplosionsDuringWar", onlyAllowExplosionsDuringWar)
        flagNoBuildDistance = config.getInt("flagNoBuildDistance", flagNoBuildDistance)
        flagNoBuildYOffset = config.getInt("flagNoBuildYOffset", flagNoBuildYOffset)
        chunkAttackTime = config.getLong("chunkAttackTime", chunkAttackTime)
        chunkAttackFromWastelandMultiplier = config.getDouble("chunkAttackFromWastelandMultiplier",
            chunkAttackFromWastelandMultiplier
        )
        chunkAttackHomeMultiplier = config.getDouble("chunkAttackHomeMultiplier", chunkAttackHomeMultiplier)
        maxPlayerChunkAttacks = config.getInt("maxPlayerChunkAttacks", maxPlayerChunkAttacks)
        allowBreakingAlliesFlags = config.getBoolean("allowBreakingAlliesFlags", allowBreakingAlliesFlags)
        flagBeaconSize = config.getInt("flagBeaconSize", flagBeaconSize)
        flagBeaconMinSkyLevel = config.getInt("flagBeaconMinSkyLevel", flagBeaconMinSkyLevel)
        flagBeaconSkyLevel = config.getInt("flagBeaconSkyLevel", flagBeaconSkyLevel)
        onlyAllowExplosionsDuringWar = config.getBoolean("onlyAllowExplosionsDuringWar", onlyAllowExplosionsDuringWar)
        warPermissions = config.getBoolean("warPermissions", warPermissions)

        allowDestructionDuringSkirmish = config.getBoolean("allowDestructionDuringSkirmish",
            allowDestructionDuringSkirmish
        )
        canLeaveTownDuringWar = config.getBoolean("canLeaveTownDuringWar", canLeaveTownDuringWar)
        canCreateTownDuringWar = config.getBoolean("canCreateTownDuringWar", canCreateTownDuringWar)
        canDestroyTownDuringWar = config.getBoolean("canDestroyTownDuringWar", canDestroyTownDuringWar)
        canLeaveNationDuringWar = config.getBoolean("canLeaveNationDuringWar", canLeaveNationDuringWar)
        annexDisabled = config.getBoolean("annexDisabled", annexDisabled)

        warWhitelist = parseUUIDSet(config, "warWhitelist")
        warUseWhitelist = warWhitelist.isNotEmpty()
        warBlacklist = parseUUIDSet(config, "warBlacklist")
        warUseBlacklist = warBlacklist.isNotEmpty()
        annexBlacklist = parseUUIDSet(config, "annexBlacklist")
        useAnnexBlacklist = annexBlacklist.isNotEmpty()

        onlyWhitelistCanAnnex = config.getBoolean("onlyWhitelistCanAnnex", onlyWhitelistCanAnnex)
        onlyWhitelistCanClaim = config.getBoolean("onlyWhitelistCanClaim", onlyWhitelistCanClaim)

        occupiedHomeTeleportMultiplier = config.getInt("occupiedHomeTeleportMultiplier", occupiedHomeTeleportMultiplier)

        allowControlInOccupiedTownList = parseUUIDSet(config, "allowControlInOccupiedTownList")
        // ======================

        // ports
        seaLevel = config.getDouble("seaLevel", seaLevel)
        portWarpTime = config.getDouble("portWarpTime", portWarpTime) * 20.0 // time is in seconds, convert to ticks
        allowPortWarpWithoutBoat = config.getBoolean("allowPortWarpWithoutBoat", allowPortWarpWithoutBoat)
    }
}

// parse global resources section in config.yml
private fun parseGlobalResources(globalResourcesSection: ConfigurationSection): TerritoryResources {
    val income: MutableMap<Material, Double> = mutableMapOf()
    val ores: MutableMap<Material, OreDeposit> = mutableMapOf()

    globalResourcesSection.getConfigurationSection("income")?.let { section ->
        for (item in section.getKeys(false)) {
            val material = Material.fromKey(item)
            if (material !== null) {
                income.put(material, section.getDouble(item))
            }
        }
    }

    globalResourcesSection.getConfigurationSection("ore")?.let { section ->
        for (item in section.getKeys(false)) {
            val material = Material.fromKey(item)
            if (material !== null) {
                // list format [chance, min, max]
                if (section.isList(item)) {
                    val list = section.getDoubleList(item)
                    if (list.size == 3) {
                        val chance = list[0]
                        val min = list[1].toInt()
                        val max = list[2].toInt()
                        ores.put(material, OreDeposit(material, chance, min, max))
                    }
                }
                // single chance number (implicit min = max = 1)
                else {
                    val chance = section.getDouble(item)
                    ores.put(material, OreDeposit(material, chance, 1, 1))
                }
            }
        }
    }

    return TerritoryResources(
        income = income,
        ores = ores,
    )
}

/**
 * Load yaml uuid string list into a hashset of uuid
 */
private fun parseUUIDSet(config: ConfigurationSection, listName: String): HashSet<UUID> {
    val uuids: HashSet<UUID> = hashSetOf()

    if (config.isList(listName)) {
        val uuidList = config.getStringList(listName)
        for (uuidString in uuidList) {
            val uuid = try {
                UUID.fromString(uuidString)
            } catch (err: Exception) {
                System.err.println("[Config] Invalid UUID: $uuidString")
                continue
            }
            uuids.add(uuid)
        }
    }

    return uuids
}
