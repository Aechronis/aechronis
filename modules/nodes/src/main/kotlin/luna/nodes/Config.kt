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
import java.nio.file.Paths
import java.util.UUID

public object Config {

    // ===================================
    // config
    // ===================================
    // where players spawn
    public var spawnLoc = Pos(27000.0, 75.0, 5800.0)

    // folder containing minecraft world
    public var pathLevel = Paths.get("C:\\Users\\Luna\\AppData\\Roaming\\PrismLauncher\\instances\\1.21.4\\minecraft\\saves\\WorldMap120")

    public var pathSpark = Paths.get("spark").normalize()

    // ===================================
    // engine configs
    // ===================================
    // main plugin path for config and saves
    public var pathPlugin = "nodes"

    // folder for backups of json state files
    public var pathBackup = Paths.get("nodes/backup").normalize()

    // file names for world, towns, war json files
    public var pathWorld = Paths.get(pathPlugin, "world.json").normalize()
    public var pathTowns = Paths.get(pathPlugin, "towns.json").normalize()
    public var pathWar = Paths.get(pathPlugin, "war.json").normalize()
    public var pathPorts = Paths.get(pathPlugin, "ports.json").normalize()
    public var pathLastBackupTime = Paths.get(pathPlugin, "lastBackupTime.txt").normalize()
    public var pathLastIncomeTime = Paths.get(pathPlugin, "lastIncomeTime.txt").normalize()

    // period for running world save
    public var savePeriod: Long = 600L

    // all long tick cycle values
    // 1 hour = 3600000 ms
    // 2 hour = 7200000 ms
    public var backupPeriod: Long = 3600000L // 1 hour

    // main tick period check for backup, income, town + resident cooldown counters
    public var mainPeriodicTick: Long = 1200L

    // nametag update period
    public var nametagPipelineTicks: Int = 16

    // ===================================
    // resource configs
    // ===================================
    // territory do income enabled and income time
    public var incomeEnabled: Boolean = true
    public var incomePeriod: Long = 3600000L

    // global resource node in all territories
    public var globalResources = TerritoryResources()

    // hidden ore blocks, stone only
    public var oreBlocks = setOf(
        Material.STONE, // note: granite, diorite, and andesite are variants of stone in 1.12.2
        Material.ANDESITE,
        Material.GRANITE,
        Material.DIORITE,
    )

    // allow mining in unowned territories
    public var allowOreInWilderness: Boolean = false

    // allow getting ore in captured territory
    public var allowOreInCaptured: Boolean = true

    // allow mining in other towns in nation
    public var allowOreInNationTowns: Boolean = true

    // ===================================
    // permissions
    // ===================================
    // interact in area with NO TERRITORIES (build, destroy, etc...)
    public var canInteractInEmpty: Boolean = false

    // interact in territory without town (build, destroy, etc...)
    public var canInteractInUnclaimed: Boolean = true

    // ===================================
    // town cooldowns
    // ===================================
    // 24 hour = 86400000 ms
    // 48 hour = 172800000 ms
    // 72 hour = 259200000 ms
    public var townCreateCooldown: Long = 172800000L

    public var townMoveHomeCooldown: Long = 172800000L

    // annexation settings
    // only allow annexing during war time
    public var canOnlyAnnexDuringWar: Boolean = true

    // ===================================
    // town settings
    // ===================================
    // town spawn timer in seconds (converted to ticks by num * 20)
    public var townSpawnTime: Int = 10

    // ===================================
    // nation settings
    // ===================================
    // allow members of the same nation to attack eachother
    public var allowNationFriendlyFire: Boolean = false

    // ===================================
    // alliance settings
    // ===================================
    // allow allied nations to attack eachother
    public var allowAllyFriendlyFire: Boolean = true

    // ===================================
    // captured territory tax rates:
    // (taxation is theft)
    // ===================================
    // fraction of territory income that goes to occupier
    public var taxIncomeRate: Double = 0.2

    // probability that a hidden ore event resources go to occupier
    public var taxMineRate: Double = 0.2

    // ===================================
    // war configs
    // ===================================
    // Nodes internal explosion block damage restrictions
    public var restrictExplosions: Boolean = true
    public var onlyAllowExplosionsDuringWar: Boolean = true

    public var flagMaterialDefault: Material = Material.OAK_FENCE

    public var flagMaterials: MutableSet<Material> = mutableSetOf<Material>(
        Material.ACACIA_FENCE,
        Material.BIRCH_FENCE,
        Material.BAMBOO_FENCE,
        Material.CHERRY_FENCE,
        Material.DARK_OAK_FENCE,
        Material.JUNGLE_FENCE,
        Material.MANGROVE_FENCE,
        Material.OAK_FENCE,
        Material.SPRUCE_FENCE,
    )

    // disable building within this distance of flag (square range)
    public var flagNoBuildDistance: Int = 1

    // disable building for y > flag base block + flagNoBuildYOffset
    public var flagNoBuildYOffset: Int = -1

    // ticks required to capture chunk
    public var chunkAttackTime: Long = 200

    // multiplier for chunk attacks
    public var chunkAttackFromWastelandMultiplier: Double = 2.0 // territory next to wilderness
    public var chunkAttackHomeMultiplier: Double = 2.0 // in home territory

    // number of chunks a player can attack at same time
    public var maxPlayerChunkAttacks: Int = 1

    // allow breaking allies war flags
    public var allowBreakingAlliesFlags = false

    // flag sky beacon config
    public var flagBeaconSize: Int = 6 // be in range [2, 16]
    public var flagBeaconMinSkyLevel: Int = 100 // minimum height level in sky
    public var flagBeaconSkyLevel: Int = 50 // height level above blocks

    // allow war permissions during skirmish mode
    public var allowDestructionDuringSkirmish: Boolean = false

    // bypass permissions and allow extended ally interactions in towns
    public var warPermissions: Boolean = true

    // allow leaving towns/natiosn during war
    public var canLeaveTownDuringWar: Boolean = true

    // allow creating towns, nation stuff during war
    public var canCreateTownDuringWar: Boolean = false
    public var canDestroyTownDuringWar: Boolean = false
    public var canLeaveNationDuringWar: Boolean = false

    // global disable annexing
    public var annexDisabled: Boolean = false

    // use whitelist/blacklist for war (derived from list.size > 0 for lists below)
    public var warUseWhitelist: Boolean = false
    public var warUseBlacklist: Boolean = false

    // war whitelist: only allow attacking these town UUIDs
    public var warWhitelist: HashSet<UUID> = hashSetOf()

    // war blacklist: disable attacking these town UUIDs
    public var warBlacklist: HashSet<UUID> = hashSetOf()

    // annex blacklist: cannot annex these towns, only occupy
    public var annexBlacklist: HashSet<UUID> = hashSetOf()
    public var useAnnexBlacklist: Boolean = false

    // whitelist settings

    // only towns in whitelist can annex territories (from other whitelist territories)
    public var onlyWhitelistCanAnnex: Boolean = true
    public var onlyWhitelistCanClaim: Boolean = true

    // multiplier for warping home when occupied
    public var occupiedHomeTeleportMultiplier: Double = 12.0

    // List of town UUIDs to allow building in occupied territory.
    // War whitelist often used to create AI towns that can be attacked
    // by anyone. People want to build in occupied territory from these
    // towns during non-war time. This list allows building/interacting
    // in these occupied towns.
    public var allowControlInOccupiedTownList: HashSet<UUID> = hashSetOf()

    // ===================================
    // port configs
    // ===================================
    public var seaLevel: Double = 62.0
    public var portWarpTime: Double = 5.0 * 20.0 // in ticks
    public var allowPortWarpWithoutBoat: Boolean = false

    // ===================================================
    // Load config
    // ===================================================
    public fun load(config: FileConfiguration) {
        // engine settings
        Config.savePeriod = config.getLong("savePeriod", Config.savePeriod)
        Config.backupPeriod = config.getLong("backupPeriod", Config.backupPeriod)
        Config.mainPeriodicTick = config.getLong("mainPeriodicTick", Config.mainPeriodicTick)
        Config.nametagPipelineTicks = config.getInt("nametagPipelineTicks", Config.nametagPipelineTicks)

        // generic permissions
        Config.canInteractInEmpty = config.getBoolean("canInteractInEmpty", Config.canInteractInEmpty)
        Config.canInteractInUnclaimed = config.getBoolean("canInteractInUnclaimed", Config.canInteractInUnclaimed)

        // town cooldown configs
        Config.townCreateCooldown = config.getLong("townCreateCooldown", Config.townCreateCooldown)
        Config.townMoveHomeCooldown = config.getLong("townMoveHomeCooldown", Config.townMoveHomeCooldown)

        // resource configs
        Config.incomeEnabled = config.getBoolean("incomeEnabled", Config.incomeEnabled)
        Config.incomePeriod = config.getLong("incomePeriod", Config.incomePeriod)
        Config.allowOreInWilderness = config.getBoolean("allowOreInWilderness", Config.allowOreInWilderness)
        Config.allowOreInCaptured = config.getBoolean("allowOreInCaptured", Config.allowOreInCaptured)
        Config.allowOreInNationTowns = config.getBoolean("allowOreInNationTowns", Config.allowOreInNationTowns)

        // global resources in all territories
        val globalResourcesSection = config.getConfigurationSection("globalResources")
        if (globalResourcesSection !== null) {
            Config.globalResources = parseGlobalResources(globalResourcesSection)
        }

        Config.canOnlyAnnexDuringWar = config.getBoolean("canOnlyAnnexDuringWar", Config.canOnlyAnnexDuringWar)

        // town settings
        Config.townSpawnTime = config.getInt("townSpawnTime", Config.townSpawnTime)

        // nation settings
        Config.allowNationFriendlyFire = config.getBoolean("allowNationFriendlyFire", Config.allowNationFriendlyFire)

        // ally settings
        Config.allowAllyFriendlyFire = config.getBoolean("allowAllyFriendlyFire", Config.allowAllyFriendlyFire)

        // tax
        Config.taxIncomeRate = config.getDouble("taxIncomeRate", Config.taxIncomeRate)
        Config.taxMineRate = config.getDouble("taxMineRate", Config.taxMineRate)

        // ======================
        // war
        // ======================
        Config.restrictExplosions = config.getBoolean("restrictExplosions", Config.restrictExplosions)
        Config.onlyAllowExplosionsDuringWar = config.getBoolean("onlyAllowExplosionsDuringWar", Config.onlyAllowExplosionsDuringWar)
        Config.flagNoBuildDistance = config.getInt("flagNoBuildDistance", Config.flagNoBuildDistance)
        Config.flagNoBuildYOffset = config.getInt("flagNoBuildYOffset", Config.flagNoBuildYOffset)
        Config.chunkAttackTime = config.getLong("chunkAttackTime", Config.chunkAttackTime)
        Config.chunkAttackFromWastelandMultiplier = config.getDouble("chunkAttackFromWastelandMultiplier", Config.chunkAttackFromWastelandMultiplier)
        Config.chunkAttackHomeMultiplier = config.getDouble("chunkAttackHomeMultiplier", Config.chunkAttackHomeMultiplier)
        Config.maxPlayerChunkAttacks = config.getInt("maxPlayerChunkAttacks", Config.maxPlayerChunkAttacks)
        Config.allowBreakingAlliesFlags = config.getBoolean("allowBreakingAlliesFlags", Config.allowBreakingAlliesFlags)
        Config.flagBeaconSize = config.getInt("flagBeaconSize", Config.flagBeaconSize)
        Config.flagBeaconMinSkyLevel = config.getInt("flagBeaconMinSkyLevel", Config.flagBeaconMinSkyLevel)
        Config.flagBeaconSkyLevel = config.getInt("flagBeaconSkyLevel", Config.flagBeaconSkyLevel)
        Config.onlyAllowExplosionsDuringWar = config.getBoolean("onlyAllowExplosionsDuringWar", Config.onlyAllowExplosionsDuringWar)
        Config.warPermissions = config.getBoolean("warPermissions", Config.warPermissions)

        Config.allowDestructionDuringSkirmish = config.getBoolean("allowDestructionDuringSkirmish", Config.allowDestructionDuringSkirmish)
        Config.canLeaveTownDuringWar = config.getBoolean("canLeaveTownDuringWar", Config.canLeaveTownDuringWar)
        Config.canCreateTownDuringWar = config.getBoolean("canCreateTownDuringWar", Config.canCreateTownDuringWar)
        Config.canDestroyTownDuringWar = config.getBoolean("canDestroyTownDuringWar", Config.canDestroyTownDuringWar)
        Config.canLeaveNationDuringWar = config.getBoolean("canLeaveNationDuringWar", Config.canLeaveNationDuringWar)
        Config.annexDisabled = config.getBoolean("annexDisabled", Config.annexDisabled)

        Config.warWhitelist = parseUUIDSet(config, "warWhitelist")
        Config.warUseWhitelist = Config.warWhitelist.size > 0
        Config.warBlacklist = parseUUIDSet(config, "warBlacklist")
        Config.warUseBlacklist = Config.warBlacklist.size > 0
        Config.annexBlacklist = parseUUIDSet(config, "annexBlacklist")
        Config.useAnnexBlacklist = Config.annexBlacklist.size > 0

        Config.onlyWhitelistCanAnnex = config.getBoolean("onlyWhitelistCanAnnex", Config.onlyWhitelistCanAnnex)
        Config.onlyWhitelistCanClaim = config.getBoolean("onlyWhitelistCanClaim", Config.onlyWhitelistCanClaim)

        Config.occupiedHomeTeleportMultiplier = config.getDouble("occupiedHomeTeleportMultiplier", Config.occupiedHomeTeleportMultiplier)

        Config.allowControlInOccupiedTownList = parseUUIDSet(config, "allowControlInOccupiedTownList")
        // ======================

        // ports
        Config.seaLevel = config.getDouble("seaLevel", Config.seaLevel)
        Config.portWarpTime = config.getDouble("portWarpTime", Config.portWarpTime) * 20.0 // time is in seconds, convert to ticks
        Config.allowPortWarpWithoutBoat = config.getBoolean("allowPortWarpWithoutBoat", Config.allowPortWarpWithoutBoat)
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
                    if (list.size === 3) {
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

// parse teleport material cost list:
// material: amount
private fun parseTeleportCost(section: ConfigurationSection): MutableMap<Material, Int> {
    val materials: MutableMap<Material, Int> = mutableMapOf()

    for (item in section.getKeys(false)) {
        val material = Material.fromKey(item)
        if (material !== null) {
            materials.put(material, section.getInt(item))
        }
    }

    return materials
}

// string format of teleport cost item list
private fun teleportCostToString(materials: MutableMap<Material, Int>): String {
    var s = ""

    var index = 0
    for ((mat, amount) in materials) {
        s += "$amount $mat"

        if (index < materials.size - 1) {
            s += ", "
        }

        index += 1
    }

    return s
}

/**
 * Load yaml uuid string list into a hashset of uuid
 */
private fun parseUUIDSet(config: ConfigurationSection, listName: String): HashSet<UUID> {
    val uuids: HashSet<UUID> = hashSetOf()

    if (config.isList(listName)) {
        val uuidList = config.getStringList(listName)
        if (uuidList !== null) {
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
    }

    return uuids
}
