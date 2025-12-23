/**
 * Config
 *
 * Contains global config state variables read in from
 * plugin config.yml file
 */

package phonon.nodes

import net.minestom.server.item.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import net.minestom.server.entity.EntityType
import phonon.nodes.objects.OreDeposit
import phonon.nodes.objects.TerritoryResources
import java.nio.file.Paths
import java.util.UUID

public object Config {

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
    public var pathTruce = Paths.get(pathPlugin, "truce.json").normalize()
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

    // period to send reminder to players that town over max claims
    public var overMaxClaimsReminderPeriod: Long = 24000L

    // nametag update period
    public var nametagPipelineTicks: Int = 16

    // ===================================
    // resource configs
    // ===================================
    // territory do income enabled and income time
    public var incomeEnabled: Boolean = true
    public var incomePeriod: Long = 3600000L

    // enable income rate scaling based on percent of town claim power used
    // e.g. if 100 claim power is needed for all territories
    // and town players have total of 60 claim power
    // then income rate is scaled by,
    //     r = (sum of player claim power) / (sum of territory claim)
    //       = 60 / 100
    //       = 0.6
    public var incomeScaleByClaimPower: Boolean = true
    public var incomeScaleMin: Double = 0.1 // minimum income scaling factor
    public var incomeScaleMax: Double = 1.0 // maximum income scaling factor, >1.0 allowed

    // global resource node in all territories
    public var globalResources = TerritoryResources()

    // hidden ore blocks, stone only
    public var oreBlocks = setOf(
        Material.STONE, // note: granite, diorite, and andesite are variants of stone in 1.12.2
        Material.ANDESITE,
        Material.GRANITE,
        Material.DIORITE,
    )

    // allow mining/harvesting/breeding in unowned territories
    public var allowOreInWilderness: Boolean = false
    public var allowBreedingInWilderness: Boolean = false

    // allow getting ore in captured territory
    public var allowOreInCaptured: Boolean = true

    // allow mining in other towns in nation
    public var allowOreInNationTowns: Boolean = true

    // required sky light for animal breeding (set = 0 to disable)
    public var breedingMinSkyLight: Int = 14

    // min/max y levels allowed for animal breeding
    public var breedingMinYHeight: Int = 10
    public var breedingMaxYHeight: Int = 255

    // ===================================
    // afk kick time
    // ===================================
    // time in milliseconds for afk time before player kicked
    // adjusts claims progress as penalty
    // 10 min = 600000 ms
    // 20 min = 1200000 ms
    public var afkKickTime: Long = 900000L

    // ===================================
    // permissions
    // ===================================
    // interact in area with NO TERRITORIES (build, destroy, etc...)
    public var canInteractInEmpty: Boolean = false

    // interact in territory without town (build, destroy, etc...)
    public var canInteractInUnclaimed: Boolean = true

    // only allow shearing in sheep node
    public var requireSheepNodeToShear: Boolean = true

    // ===================================
    // town cooldowns
    // ===================================
    // 24 hour = 86400000 ms
    // 48 hour = 172800000 ms
    // 72 hour = 259200000 ms
    public var townCreateCooldown: Long = 172800000L

    public var townMoveHomeCooldown: Long = 172800000L

    // ===================================
    // town claim configs
    // ===================================
    // territory cost = base + scale * chunks
    public var territoryCostBase: Int = 10
    public var territoryCostScale: Double = 0.25

    // initial player given on town creation
    public var townInitialClaims: Int = 25

    // penalty scale factor when over initial claim allowed:
    // penalty = scale * (territory.cost - initialAllowed) if cost > allowed
    public var initialOverClaimsAmountScale: Int = 2

    // base number of claim power per town
    public var townClaimsBase: Int = 20

    // max claim power per town (-1 for unlimited)
    public var townClaimsMax: Int = -1

    // town penalty decay amount
    public var townPenaltyDecay: Int = 2

    // claim power per player
    public var playerClaimsInitial: Int = 0 // initial player claims on town join
    public var playerClaimsMax: Int = 20 // max player claims for town
    public var playerClaimsIncrease: Int = 1 // claims increase per tick period

    // time periods for town claims penalty decay and player power gain, milliseconds
    // 1 hour = 3600000 ms
    // 2 hour = 7200000 ms
    public var townClaimsPenaltyDecayPeriod: Long = 3600000L
    public var playerClaimsIncreasePeriod: Long = 3600000L

    // flag for enforcing over max claims
    // for now, this affects all systems (income, hidden ore,
    // breeding, etc...)
    // TODO: make fine grained over claims penalty for each system
    public var overClaimsPenalty: Boolean = true

    // reduced resource rate when over max claim (runs Math.random() < rate)
    public var overClaimsMaxPenalty: Double = 0.5

    // allow claiming territories even if town already over max claims
    public var overClaimsAllowClaim: Boolean = false

    // annexation settings
    // only allow annexing during war time
    public var canOnlyAnnexDuringWar: Boolean = true

    // ===================================
    // town settings
    // ===================================
    // town spawn timer in seconds (converted to ticks by num * 20)
    public var townSpawnTime: Int = 10

    // outpost configs
    public val outpostTeleportCost: MutableMap<Material, Int> = mutableMapOf()

    // inline string list of outpost teleport cost
    public var outpostTeleportCostString: String = ""

    // ===================================
    // nation settings
    // ===================================
    // allow spawning in nation towns
    public var allowNationTownSpawn: Boolean = false

    // cost for nation town spawn
    public val nationTownTeleportCost: MutableMap<Material, Int> = mutableMapOf()

    // inline string list of outpost teleport cost
    public var nationTownTeleportCostString: String = ""

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

    // probability that a new animal goes to occupier (as a spawn egg item)
    public var taxAnimalRate: Double = 0.2

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
    // truce configs
    // ===================================

    // truce period in milliseconds
    // (truce counter uses System.currentTimeMillis)
    // 24 hour = 86400000 ms
    // 48 hour = 172800000 ms
    // 72 hour = 259200000 ms
    public var trucePeriod: Long = 172800000L

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
        Config.overMaxClaimsReminderPeriod = config.getLong("overMaxClaimsReminderPeriod", Config.overMaxClaimsReminderPeriod)
        Config.nametagPipelineTicks = config.getInt("nametagPipelineTicks", Config.nametagPipelineTicks)

        // afk kick time
        Config.afkKickTime = config.getLong("afkKickTime", Config.afkKickTime)

        // generic permissions
        Config.canInteractInEmpty = config.getBoolean("canInteractInEmpty", Config.canInteractInEmpty)
        Config.canInteractInUnclaimed = config.getBoolean("canInteractInUnclaimed", Config.canInteractInUnclaimed)
        Config.requireSheepNodeToShear = config.getBoolean("requireSheepNodeToShear", Config.requireSheepNodeToShear)

        // town cooldown configs
        Config.townCreateCooldown = config.getLong("townCreateCooldown", Config.townCreateCooldown)
        Config.townMoveHomeCooldown = config.getLong("townMoveHomeCooldown", Config.townMoveHomeCooldown)

        // resource configs
        Config.incomeEnabled = config.getBoolean("incomeEnabled", Config.incomeEnabled)
        Config.incomePeriod = config.getLong("incomePeriod", Config.incomePeriod)
        Config.incomeScaleByClaimPower = config.getBoolean("incomeScaleByClaimPower", Config.incomeScaleByClaimPower)
        Config.incomeScaleMin = config.getDouble("incomeScaleMin", Config.incomeScaleMin)
        Config.incomeScaleMax = config.getDouble("incomeScaleMax", Config.incomeScaleMax)
        Config.allowOreInWilderness = config.getBoolean("allowOreInWilderness", Config.allowOreInWilderness)
        Config.allowBreedingInWilderness = config.getBoolean("allowBreedingInWilderness", Config.allowBreedingInWilderness)
        Config.allowOreInCaptured = config.getBoolean("allowOreInCaptured", Config.allowOreInCaptured)
        Config.allowOreInNationTowns = config.getBoolean("allowOreInNationTowns", Config.allowOreInNationTowns)
        Config.breedingMinSkyLight = config.getInt("breedingMinSkyLight", Config.breedingMinSkyLight)
        Config.breedingMinYHeight = config.getInt("breedingMinYHeight", Config.breedingMinYHeight)
        Config.breedingMaxYHeight = config.getInt("breedingMaxYHeight", Config.breedingMaxYHeight)

        // global resources in all territories
        val globalResourcesSection = config.getConfigurationSection("globalResources")
        if (globalResourcesSection !== null) {
            Config.globalResources = parseGlobalResources(globalResourcesSection)
        }

        // town claims
        Config.territoryCostBase = config.getInt("territoryCostBase", Config.territoryCostBase)
        Config.territoryCostScale = config.getDouble("territoryCostScale", Config.territoryCostScale)
        Config.townInitialClaims = config.getInt("townInitialClaims", Config.townInitialClaims)
        Config.initialOverClaimsAmountScale = config.getInt("initialOverClaimsAmountScale", Config.initialOverClaimsAmountScale)
        Config.townClaimsBase = config.getInt("townClaimsBase", Config.townClaimsBase)
        Config.townClaimsMax = config.getInt("townClaimsMax", Config.townClaimsMax)

        Config.playerClaimsInitial = config.getInt("playerClaimsInitial", Config.playerClaimsInitial)
        Config.playerClaimsMax = config.getInt("playerClaimsMax", Config.playerClaimsMax)
        Config.playerClaimsIncrease = config.getInt("playerClaimsIncrease", Config.playerClaimsIncrease)

        Config.townPenaltyDecay = config.getInt("townPenaltyDecay", Config.townPenaltyDecay)
        Config.townClaimsPenaltyDecayPeriod = config.getLong("townClaimsPenaltyDecayPeriod", Config.townClaimsPenaltyDecayPeriod)
        Config.playerClaimsIncreasePeriod = config.getLong("playerClaimsIncreasePeriod", Config.playerClaimsIncreasePeriod)
        Config.overClaimsPenalty = config.getBoolean("overClaimsPenalty", Config.overClaimsPenalty)
        Config.overClaimsMaxPenalty = config.getDouble("overClaimsMaxPenalty", Config.overClaimsMaxPenalty)
        Config.overClaimsAllowClaim = config.getBoolean("overClaimsAllowClaim", Config.overClaimsAllowClaim)

        Config.canOnlyAnnexDuringWar = config.getBoolean("canOnlyAnnexDuringWar", Config.canOnlyAnnexDuringWar)

        // town settings
        Config.townSpawnTime = config.getInt("townSpawnTime", Config.townSpawnTime)
        val outpostTeleportCostSection = config.getConfigurationSection("outpostTeleportCost")
        if (outpostTeleportCostSection !== null) {
            Config.outpostTeleportCost.putAll(parseTeleportCost(outpostTeleportCostSection))
            Config.outpostTeleportCostString = teleportCostToString(Config.outpostTeleportCost)
        }

        // nation settings
        Config.allowNationTownSpawn = config.getBoolean("allowNationTownSpawn", Config.allowNationTownSpawn)
        val nationTeleportCostSection = config.getConfigurationSection("nationTownTeleportCost")
        if (nationTeleportCostSection !== null) {
            Config.nationTownTeleportCost.putAll(parseTeleportCost(nationTeleportCostSection))
            Config.nationTownTeleportCostString = teleportCostToString(Config.nationTownTeleportCost)
        }
        Config.allowNationFriendlyFire = config.getBoolean("allowNationFriendlyFire", Config.allowNationFriendlyFire)

        // ally settings
        Config.allowAllyFriendlyFire = config.getBoolean("allowAllyFriendlyFire", Config.allowAllyFriendlyFire)

        // tax
        Config.taxIncomeRate = config.getDouble("taxIncomeRate", Config.taxIncomeRate)
        Config.taxMineRate = config.getDouble("taxMineRate", Config.taxMineRate)
        Config.taxAnimalRate = config.getDouble("taxAnimalRate", Config.taxAnimalRate)

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

        // truce
        Config.trucePeriod = config.getLong("trucePeriod", Config.trucePeriod)

        // ports
        Config.seaLevel = config.getDouble("seaLevel", Config.seaLevel)
        Config.portWarpTime = config.getDouble("portWarpTime", Config.portWarpTime) * 20.0 // time is in seconds, convert to ticks
        Config.allowPortWarpWithoutBoat = config.getBoolean("allowPortWarpWithoutBoat", Config.allowPortWarpWithoutBoat)
    }
}

// parse global resources section in config.yml
private fun parseGlobalResources(globalResourcesSection: ConfigurationSection): TerritoryResources {
    val income: MutableMap<Material, Double> = mutableMapOf()
    val incomeSpawnEgg: MutableMap<EntityType, Double> = mutableMapOf()
    val ores: MutableMap<Material, OreDeposit> = mutableMapOf()
    val animals: MutableMap<EntityType, Double> = mutableMapOf()

    globalResourcesSection.getConfigurationSection("income")?.let { section ->
        for (item in section.getKeys(false)) {
            val itemName = item.uppercase()
            // spawn egg
            if (itemName.startsWith("SPAWN_EGG_")) {
                val entityType = EntityType.fromKey(itemName.replace("SPAWN_EGG_", ""))
                if (entityType !== null) {
                    incomeSpawnEgg.put(entityType, section.getDouble(item))
                }
            }
            // regular material
            else {
                val material = Material.fromKey(item)
                if (material !== null) {
                    income.put(material, section.getDouble(item))
                }
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

    globalResourcesSection.getConfigurationSection("animals")?.let { section ->
        for (item in section.getKeys(false)) {
            try {
                val entityType = EntityType.fromKey(item.uppercase())
                animals.put(entityType!!, section.getDouble(item))
            } catch (err: Exception) {
                err.printStackTrace()
            }
        }
    }

    return TerritoryResources(
        income = income,
        incomeSpawnEgg = incomeSpawnEgg,
        ores = ores,
        animals = animals,
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
