///*
// * Nodes Engine/API
// */

package luna.nodes

import com.google.gson.JsonObject
import luna.nodes.commands.AllyCommand
import luna.nodes.commands.NationCommand
import luna.nodes.commands.NodesCommand
import luna.nodes.commands.TownCommand
import luna.nodes.commands.UnallyCommand
import luna.nodes.commands.WarCommand
//import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.minestom.server.MinecraftServer
import luna.nodes.utils.ChatColor
import net.minestom.server.instance.Chunk
import net.minestom.server.coordinate.Pos
import net.minestom.server.item.Material
//import org.bukkit.Particle
//import org.bukkit.World
//import org.bukkit.block.Block
//import org.bukkit.block.Chest
//import org.bukkit.block.DoubleChest
import net.minestom.server.entity.Player
import net.minestom.server.inventory.Inventory
//import org.bukkit.plugin.Plugin
//import luna.nodes.chat.ChatMode
import luna.nodes.constants.DiplomaticRelationship
import luna.nodes.constants.ErrorAlreadyAllies
import luna.nodes.constants.ErrorAlreadyEnemies
import luna.nodes.constants.ErrorNationDoesNotHaveTown
import luna.nodes.constants.ErrorNationExists
import luna.nodes.constants.ErrorNotAllies
import luna.nodes.constants.ErrorPlayerHasNation
import luna.nodes.constants.ErrorPlayerHasTown
import luna.nodes.constants.ErrorPlayerNotInTown
import luna.nodes.constants.ErrorPortExists
//import luna.nodes.constants.ErrorPortInGroup
import luna.nodes.constants.ErrorTerritoryHasClaim
import luna.nodes.constants.ErrorTerritoryIsTownHome
import luna.nodes.constants.ErrorTerritoryNotConnected
import luna.nodes.constants.ErrorTerritoryNotInTown
import luna.nodes.constants.ErrorTerritoryOwned
import luna.nodes.constants.ErrorTownDoesNotExist
import luna.nodes.constants.ErrorTownExists
import luna.nodes.constants.ErrorTownHasNation
import luna.nodes.constants.ErrorWarAlly
import luna.nodes.constants.PermissionsGroup
import luna.nodes.constants.TownPermissions
import luna.nodes.listeners.onBlockBreak
import luna.nodes.listeners.onBlockBreakSuccess
import luna.nodes.listeners.onBlockPlace
import luna.nodes.listeners.onBlockPlaceSuccess
import luna.nodes.listeners.onInventoryClick
import luna.nodes.listeners.onInventoryClose
import luna.nodes.listeners.onPlayerChat
import luna.nodes.listeners.onPlayerJoin
import luna.nodes.listeners.onPlayerMove
import luna.nodes.listeners.onPlayerQuit
import luna.nodes.listeners.onPlayerTeleport
import luna.nodes.objects.Coord
import luna.nodes.objects.DefaultResourceAttributeLoader
//import luna.nodes.objects.Nametag
import luna.nodes.objects.Nation
import luna.nodes.objects.NationPair
import luna.nodes.objects.OreBlockCache
import luna.nodes.objects.OreSampler
import luna.nodes.objects.Port
import luna.nodes.objects.PortGroup
import luna.nodes.objects.Resident
import luna.nodes.objects.ResourceNode
import luna.nodes.objects.Territory
import luna.nodes.objects.TerritoryChunk
import luna.nodes.objects.TerritoryId
import luna.nodes.objects.TerritoryPreprocessing
import luna.nodes.objects.TerritoryResources
import luna.nodes.objects.Town
import luna.nodes.objects.TownPair
import luna.nodes.serdes.Deserializer
import luna.nodes.tasks.PeriodicTickManager
import luna.nodes.tasks.SaveManager
import luna.nodes.tasks.TaskSaveBackup
import luna.nodes.tasks.TaskSavePorts
import luna.nodes.tasks.TaskSaveWorld
import luna.nodes.utils.Color
import luna.nodes.utils.loadLongFromFile
import luna.nodes.utils.sanitizeString
import luna.nodes.utils.saveStringToFile
import luna.nodes.war.FlagWar
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerChatEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerLoadedEvent
import net.minestom.server.event.player.PlayerMoveEvent
import java.nio.file.Files
//import java.nio.file.Path
//import java.nio.file.Paths
//import java.nio.file.StandardCopyOption
//import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadLocalRandom
//import java.util.concurrent.TimeUnit
//import java.util.logging.Logger
import kotlin.system.measureNanoTime

/**
 * Nodes container
 */
object Nodes {

    // library of resource node definitions
    internal val resourceNodes: HashMap<String, ResourceNode> = hashMapOf()

    // world grid that maps coord -> territory chunk wrapper
    internal val territoryChunks: HashMap<Coord, TerritoryChunk> = hashMapOf()

    // map territory id -> Territory
    internal val territories: HashMap<TerritoryId, Territory> = hashMapOf()

    // list of all towns
    internal val towns: LinkedHashMap<String, Town> = LinkedHashMap()

    // list of all nations
    internal val nations: LinkedHashMap<String, Nation> = LinkedHashMap()

    // map player UUID -> Resident player wrapper
    internal val residents: LinkedHashMap<UUID, Resident> = LinkedHashMap()

    // ports system
    internal val ports: LinkedHashMap<String, Port> = LinkedHashMap()
    internal val portGroups: LinkedHashMap<String, PortGroup> = LinkedHashMap()
//
//    // map of player -> task for warping
//    public var playerWarpTasks: HashMap<UUID, ScheduledTask> = hashMapOf()

    // map chunk coords -> port, assumes one chunk only has 1 port
    var chunkToPort: HashMap<List<Int>, Port> = hashMapOf()

    // last time backup occurred: NOTE this is accessed async
    internal var lastBackupTime: Long = 0 // milliseconds

    // last time income occurred: NOTE this is accessed async
    internal var lastIncomeTime: Long = 0 // milliseconds

    // war manager
    val war = FlagWar

    // flag that world was updated and needs save
    internal var needsSave: Boolean = false

    // set of invalid block locations for hidden ore drops
    internal val hiddenOreInvalidBlocks: OreBlockCache = OreBlockCache(2000)

    // configuration
    lateinit var config: NodesConfig

    fun initialize(config: NodesConfig = NodesConfig()) {
        // measure load time
        val timeStart = System.currentTimeMillis()

        // store config
        this.config = config

        war.initialize(config.flagBlocks)

        // try load world
        val pluginPath = config.pathPlugin
        println("Loading world from: $pluginPath")
        try {
            if (loadWorld()) { // successful load
                // print number of resource nodes and territories loaded
                println("- Resource Nodes: ${getResourceNodeCount()}")
                println("- Territories: ${getTerritoryCount()}")
                println("- Residents: ${getResidentCount()}")
                println("- Towns: ${getTownCount()}")
                println("- Nations: ${getNationCount()}")
            } else {
                println("Error loading world: Invalid world file at $pluginPath/${config.pathWorld}")
            }
        } catch (err: Exception) {
            err.printStackTrace()
            println("Error loading world: $err")
        }

        val eventHandler = MinecraftServer.getGlobalEventHandler()

        // register listeners
        eventHandler.addListener(PlayerChatEvent::class.java) { event -> onPlayerChat(event) }
//    pluginManager.registerEvents(NodesChestProtectionListener(), this)
//    pluginManager.registerEvents(NodesChestProtectionDestroyListener(), this)
        eventHandler.addListener(InventoryPreClickEvent::class.java) { event -> onInventoryClick(event) }
        eventHandler.addListener(InventoryCloseEvent::class.java) { event -> onInventoryClose(event) }
        eventHandler.addListener(PlayerBlockBreakEvent::class.java) { event -> onBlockBreak(event) }
        eventHandler.addListener(PlayerBlockBreakEvent::class.java) { event -> onBlockBreakSuccess(event) }
        eventHandler.addListener(PlayerBlockPlaceEvent::class.java) { event -> onBlockPlace(event) }
        eventHandler.addListener(PlayerBlockPlaceEvent::class.java) { event -> onBlockPlaceSuccess(event) }
//    pluginManager.registerEvents(NodesPlayerJoinQuitListener(), this)
        eventHandler.addListener(PlayerLoadedEvent::class.java) { event -> onPlayerJoin(event) }
        eventHandler.addListener(PlayerDisconnectEvent::class.java) { event -> onPlayerQuit(event) }
        eventHandler.addListener(PlayerMoveEvent::class.java) { event -> onPlayerMove(event) }
        eventHandler.addListener(EntityTeleportEvent::class.java) { event -> onPlayerTeleport(event) }
//    pluginManager.registerEvents(NodesPlayerDamageListener(), this)

        // shutdown task
        MinecraftServer.getSchedulerManager().buildShutdownTask { cleanup() }

//    // register commands
        MinecraftServer.getCommandManager().register(TownCommand())
        MinecraftServer.getCommandManager().register(NationCommand())
        MinecraftServer.getCommandManager().register(NodesCommand())
//    this.getCommand("nodesadmin")?.setExecutor(NodesAdminCommand())
        MinecraftServer.getCommandManager().register(AllyCommand())
        MinecraftServer.getCommandManager().register(UnallyCommand())
        MinecraftServer.getCommandManager().register(WarCommand())
//    this.getCommand("globalchat")?.setExecutor(GlobalChatCommand())
//    this.getCommand("townchat")?.setExecutor(TownChatCommand())
//    this.getCommand("nationchat")?.setExecutor(NationChatCommand())
//    this.getCommand("allychat")?.setExecutor(AllyChatCommand())
//    this.getCommand("player")?.setExecutor(PlayerCommand())
//    this.getCommand("territory")?.setExecutor(TerritoryCommand())
//    this.getCommand("port")?.setExecutor(PortCommand())

        // load current income tick
        val currTime = System.currentTimeMillis()
        lastBackupTime = loadLongFromFile(config.pathLastBackupTime) ?: currTime
        lastIncomeTime = loadLongFromFile(config.pathLastIncomeTime) ?: currTime

        // run background schedulers/tasks
        reloadManagers()

        // initialize all players online
        initializeOnlinePlayers()

        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Enabled in ${timeLoad}ms")

        // print success message
        println("now this is epic")
    }

    /**
     * Reload background managers/tasks
     */
    internal fun reloadManagers() {
        SaveManager.stop()
        PeriodicTickManager.stop()

        SaveManager.start(config.savePeriod)
        PeriodicTickManager.start(config.mainPeriodicTick)
    }

    // mark all current players in game as online
    // needed to correctly mark online players after reloading plugin
    internal fun initializeOnlinePlayers() {
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            // create resident for player if it does not exist
            createResident(player)

            // mark player online
            val resident = getResident(player)!!
            setResidentOnline(resident, player)
        }

//        // update nametags
//        Nametag.pipelinedUpdateAllText()
    }

    // clean up any world details
    // run when plugin disabled
    internal fun cleanup() {
        // cleanup residents
        for (r in residents.values) {
            // remove minimaps
            r.destroyMinimap()
        }

        // force push all town income items from inventory gui
        // back to storage data structure
        for (town in towns.values) {
            val result = town.income.pushToStorage(true)
            if (result) { // has moved items
                town.needsUpdate()
            }
        }

        // cleanup war if its enabled
        if (war.enabled) {
            war.cleanup()
        }

        // final synchronous save of world
        saveWorld(checkIfNeedsSave = false, async = false)

        // save backup, income current time
        val currTimeString = System.currentTimeMillis().toString()
        saveStringToFile(currTimeString, config.pathLastBackupTime)
        saveStringToFile(currTimeString, config.pathLastIncomeTime)
    }

    /**
     * Load resource nodes from json files and insert them into
     * the global `Nodes.resourceNodes` map.
     */
    internal fun loadResources(json: JsonObject) {
        // TODO: generalize to multipler loaders
        resourceNodes.putAll(DefaultResourceAttributeLoader.load(json))
    }

    /**
     * Load territories from json and insert into the global
     * `Nodes.territories` map. Raises exceptions if any territory ids
     * do not exist.
     */
    internal fun loadTerritories(json: JsonObject, ids: List<TerritoryId>? = null) {
        val territoryPreprocessing: List<TerritoryPreprocessing> = TerritoryPreprocessing.loadFromJson(json, ids)

        // first create intermediary resource graph before creating
        // final compiled territories.
        // - if ids == null, we are re-creating all territories, so this
        // will be populated with resources from all territories
        // - if ids != null, we are re-creating only a subset of territories,
        // so we need to also pre-populate this with resources from existing
        // territory neighbors AND NEIGHBOR's NEIGHBORS. This is because we cannot
        // know if neighbor's neighbors also have neighbor modifiers, so this
        // must pre-emptively load neighbor's neighbors resources.
        val terrResourceGraph: HashMap<TerritoryId, TerritoryResources> = HashMap(0)

        if (ids != null) { // add neighbor territories to terrResourceGraph
            val neighborResourcesToLoad = HashSet<TerritoryId>()

            for (id in ids) {
                val currTerr = territories[id]
                if (currTerr != null) {
                    // add neighbor territory resources into territory resource graph
                    for (neighborId in currTerr.neighbors) {
                        val neighborTerr = territories[neighborId]
                        if (neighborTerr != null) {
                            neighborResourcesToLoad.add(neighborId)
                            // also add neighbor's neighbor ids
                            for (nnId in neighborTerr.neighbors) {
                                neighborResourcesToLoad.add(nnId)
                            }
                        } else {
                            println("`loadTerritories()` Territory $id neighbor $neighborId does not exist")
                        }
                    }
                } else {
                    println("`loadTerritories()` reloading territory $id does not exist")
                }
            }

            for (id in neighborResourcesToLoad) {
                val neighborTerr = territories[id]
                if (neighborTerr != null) {
                    val resources = neighborTerr.resourceNodes
                        .map { name -> resourceNodes[name] ?: throw Exception("Resource node '$name' does not exist (for territory id=${neighborTerr.id})") }
                        .sortedBy { r -> r.priority }

                    terrResourceGraph[id] = resources.fold(config.globalResources.copy()) { terr, r -> r.apply(terr) }
                } else {
                    println("`loadTerritories()` neighbor territory $id does not exist")
                }
            }
        }

        // adds territories to be loaded to terrResourceGraph
        for (terr in territoryPreprocessing) {
            val resources = terr.resourceNodes
                .map { name -> resourceNodes[name] ?: throw Exception("Resource node '$name' does not exist (for territory id=${terr.id})") }
                .sortedBy { r -> r.priority }

            terrResourceGraph[terr.id] = resources.fold(config.globalResources.copy()) { tr, r -> r.apply(tr) }
        }

        // Determine final territories to be built.
        // If ids == null, then all territories in preprocessing phase are built.
        // If ids != null, then all territories in preprocessing AND
        //    their neighbors IF territory has neighbor modifiers.
        val territoriesToBuild: List<TerritoryPreprocessing> = if (ids == null) {
            territoryPreprocessing
        } else {
            val neighborIdsToRebuild = HashSet<TerritoryId>()
            for (terr in territoryPreprocessing) {
                if (terrResourceGraph[terr.id]!!.hasNeighborModifier) {
                    for (neighborId in terr.neighbors) {
                        neighborIdsToRebuild.add(neighborId)
                    }
                }
            }

            // remove main reloaded territory ids to avoid double-counting
            for (terr in territoryPreprocessing) {
                neighborIdsToRebuild.remove(terr.id)
            }

            val terrNeighborsToRebuild =
                neighborIdsToRebuild.mapNotNull { id -> territories.get(id)?.toPreprocessing() }

            territoryPreprocessing + terrNeighborsToRebuild
        }

        // Apply neighboring territory modifier properties to territories to be built
        for (terr in territoriesToBuild) {
            val terrResources = terrResourceGraph[terr.id]
            if (terrResources != null) {
                var terrAfterNeighborModifiers: TerritoryResources = terrResources // required assignment to ensure terrAfterNeighborModifiers is not null
                for (neighborId in terr.neighbors) {
                    terrResourceGraph[neighborId]?.let { neighborResources ->
                        if (neighborResources.hasNeighborModifier) {
                            terrAfterNeighborModifiers = terrAfterNeighborModifiers.accumulateNeighborModifiers(neighborResources)
                        }
                    }
                }
                terrResourceGraph[terr.id] = terrAfterNeighborModifiers
            } else {
                println("`loadTerritories()` Invalid territory id: ${terr.id}")
            }
        }

        // merge territory structural properties and resources
        // to create final territory
        for (t in territoriesToBuild) {
            // ensure coreChunk inside chunks
            if (!t.chunks.contains(t.core)) {
                println("[Nodes] Territory ${t.id} chunk does not contain core")
                return
            }

            // get resources and apply all accumulated neighbor modifiers
            val resources = terrResourceGraph[t.id]!!.applyNeighborModifiers()

            // sorted resource names
            val resourceNamesSorted = t.resourceNodes.sortedBy { name -> resourceNodes[name]!!.priority }

            // create OreSampler from ores map
            val ores = OreSampler(ArrayList(resources.ores.values))

            // create territory
            val territory = Territory(
                id = t.id,
                name = t.name,
                color = t.color,
                core = t.core,
                chunks = t.chunks,
                bordersWilderness = t.bordersWilderness,
                neighbors = t.neighbors,
                resourceNodes = resourceNamesSorted,
                income = resources.income,
                ores = ores,
                attackerTimeMultiplier = resources.attackerTimeMultiplier,
                defenderTimeMultiplier = resources.defenderTimeMultiplier,
            )

            // if previous territory existed, first do cleanup and copy mutable ingame properties
            territories[t.id]?.let { oldTerritory ->
                // remove old territory chunks
                oldTerritory.chunks.forEach { c -> territoryChunks.remove(c) }
                // copy town and occupier
                territory.town = oldTerritory.town
                territory.occupier = oldTerritory.occupier
            }

            // set territory
            territories.put(t.id, territory)

            // create territory chunks in world grid and map to territory
            t.chunks.forEach { c ->
                territoryChunks.put(c, TerritoryChunk(c, territory))
            }
        }
    }

//    /**
//     * Wrapper for reloading resources or territories.
//     * Returns boolean if reload was successful.
//     */
//    internal fun reloadWorldJson(
//        reloadResources: Boolean,
//        reloadTerritories: Boolean,
//        territoryIds: List<TerritoryId>? = null,
//    ): Boolean {
//        if (Files.exists(config.pathWorld)) {
//            val (jsonResources, jsonTerritories) = Deserializer.worldFromJson(config.pathWorld)
//
//            // if resources are reloaded, ALL territories must be updated (ignore ids input)
//            if (reloadResources && jsonResources != null) {
//                Nodes.loadResources(jsonResources)
//                if (jsonTerritories != null) Nodes.loadTerritories(jsonTerritories)
//            }
//            // just reload territories specified
//            else if (reloadTerritories && jsonTerritories != null) {
//                Nodes.loadTerritories(jsonTerritories, territoryIds)
//            }
//
//            return true
//        }
//
//        return false
//    }

    // load world from path
    // returns status of world load:
    // true - successful load
    // false - failed
    internal fun loadWorld(): Boolean {
        // clear storage
        resourceNodes.clear()
        territoryChunks.clear()
        territories.clear()
        towns.clear()
        nations.clear()
        residents.clear()
        ports.clear()

        // load world from JSON storage
        if (Files.exists(config.pathWorld)) {
            val (jsonResources, jsonTerritories) = Deserializer.worldFromJson(config.pathWorld)
            if (jsonResources != null) loadResources(jsonResources)
            if (jsonTerritories != null) loadTerritories(jsonTerritories)

            // load towns from json after main world load finishes
            if (Files.exists(config.pathTowns)) {
                Deserializer.townsFromJson(config.pathTowns)

                // pre-generate initial json strings for all world objects
                // (speeds up first save)
                for (resident in residents.values) {
                    resident.getSaveState()
                }
                for (town in towns.values) {
                    town.getSaveState()
                }
                for (nation in nations.values) {
                    nation.getSaveState()
                }

                // load war state
                war.load()
            } else {
                System.err.println("No towns found: ${config.pathTowns}")
                return true
            }

            // load ports from json
            if (Files.exists(config.pathPorts)) {
                Deserializer.portsFromJson(config.pathPorts)

                // pre-generate initial json strings for all port objects
                // (speeds up first save)
                for (port in ports.values) {
                    port.getSaveState()
                }
                for (portGroup in portGroups.values) {
                    portGroup.getSaveState()
                }

            } else {
                System.err.println("No ports found: ${config.pathPorts}")
                return true
            }
        } else {
            System.err.println("Failed to load world: ${config.pathWorld}")
            return false
        }

        return true
    }

    /**
     * Save world to JSON storage.
     */
    internal fun saveWorld(
        checkIfNeedsSave: Boolean = true, // set to false to force save
        async: Boolean = false, // run serialization and file write asynchronously
    ) {
        if (!config.save) {
            return
        }

        // if we reached backup time interval, generate a backup millis
        // timestamp for save task, which will be used to create a
        // timestamped backup file
        val currTime = System.currentTimeMillis()
        val backup = currTime > lastBackupTime + config.backupPeriod
        val backupTimestamp = if (backup) {
            lastBackupTime = currTime
            currTime
        } else {
            null
        }

        if (needsSave || !checkIfNeedsSave) {
            // world pre-processing
            saveWorldPreprocess()

            val timeUpdate = measureNanoTime {
                // create a snapshot of world objects state
                // always do synchronously on main thread to keep world consistent
                val residentsSnapshot = residents.values.map { it.getSaveState() }
                val townsSnapshot = towns.values.map { it.getSaveState() }
                val nationsSnapshot = nations.values.map { it.getSaveState() }

                // save task manages:
                // 1. serialize individual objects into json strings and combine into a full json string
                // 2. write file
                // 3. save backup if reached backup interval
                val taskSave = TaskSaveWorld(
                    residentsSnapshot,
                    townsSnapshot,
                    nationsSnapshot,
                    backupTimestamp,
                )

                if (async) {
                    CompletableFuture.runAsync { taskSave.run() }
                } else {
                    taskSave.run()
                }

                needsSave = false
            }

            println("[Nodes] Saving world: ${timeUpdate}ns")

            // save ports
            // create a snapshot of port objects state
            val portGroupsSnapshot = portGroups.values.map { it.getSaveState() }
            val portsSnapshot = ports.values.map { it.getSaveState() }

            val taskSavePorts = TaskSavePorts(
                portsSnapshot,
                portGroupsSnapshot,
                config.pathPorts,
            )

            if (async) {
                CompletableFuture.runAsync { taskSavePorts.run() }
            } else {
                taskSavePorts.run()
            }
        }
        // no new save needed...just do backup if we reached backup interval
        else if (backup) {
            val taskBackup = TaskSaveBackup(backupTimestamp!!)
            if (async) {
                CompletableFuture.runAsync { taskBackup.run() }
            } else {
                taskBackup.run()
            }
        }
    }

    /**
     * Handle any pre-processing or finishing town/nation modifications before
     * saving to json.
     */
    internal fun saveWorldPreprocess() {
        // move all town income items from inventory gui
        // back to storage data structure
        for (town in towns.values) {
            val result = town.income.pushToStorage(false)
            if (result) { // has moved items
                town.needsUpdate()
            }
        }
    }

    // initialization function,
    // loads diplomatic relations (allies, enemies)
    // requires all towns, nations already be created
    internal fun loadDiplomacy(
        towns: ArrayList<Town>,
        townAllies: ArrayList<ArrayList<String>>,
        townEnemies: ArrayList<ArrayList<String>>,
    ) {
        // perform fixes on diplomacy during loading:
        // 1. if towns are in nation, ignore pairs between towns
        //    in nation. instead add nations to nationAlliancePairs
        // 2. if either town NOT in nation, add to townAlliancePairs

        val townAlliancePairs: HashSet<TownPair> = hashSetOf()
        val nationAlliancePairs: HashSet<NationPair> = hashSetOf()

        val townEnemyPairs: HashSet<TownPair> = hashSetOf()
        val nationEnemyPairs: HashSet<NationPair> = hashSetOf()

        for ((i, town) in towns.withIndex()) {
            val allies = townAllies[i]
            val enemies = townEnemies[i]

            val townNation = town.nation

            for (name in allies) {
                val other = Nodes.towns.get(name)
                if (other !== null) {
                    val otherNation = other.nation

                    // only add individual town pair if either does not have a nation
                    if (townNation === null || otherNation === null) {
                        townAlliancePairs.add(TownPair(town, other))
                    }
                    // add nation pairs
                    else if (town === townNation.capital && other === otherNation.capital) {
                        nationAlliancePairs.add(NationPair(townNation, otherNation))
                    }
                }
            }

            for (name in enemies) {
                val other = Nodes.towns.get(name)
                if (other !== null) {
                    val otherNation = other.nation

                    // only add individual town pair if either does not have a nation
                    if (townNation === null || otherNation === null) {
                        townEnemyPairs.add(TownPair(town, other))
                    }
                    // add nation pairs
                    else if (town === townNation.capital && other === otherNation.capital) {
                        nationEnemyPairs.add(NationPair(townNation, otherNation))
                    }
                }
            }
        }

        // apply ally pairs
        for (townPair in townAlliancePairs) {
            val town1 = townPair.town1
            val town2 = townPair.town2

            town1.allies.add(town2)
            town2.allies.add(town1)
        }

        for (nationPair in nationAlliancePairs) {
            val nation1 = nationPair.nation1
            val nation2 = nationPair.nation2

            nation1.allies.add(nation2)
            nation2.allies.add(nation1)

            for (town1 in nation1.towns) {
                for (town2 in nation2.towns) {
                    town1.allies.add(town2)
                    town2.allies.add(town1)
                }
            }
        }

        // apply enemy pairs
        for (townPair in townEnemyPairs) {
            val town1 = townPair.town1
            val town2 = townPair.town2

            town1.enemies.add(town2)
            town2.enemies.add(town1)
        }

        for (nationPair in nationEnemyPairs) {
            val nation1 = nationPair.nation1
            val nation2 = nationPair.nation2

            nation1.enemies.add(nation2)
            nation2.enemies.add(nation1)

            for (town1 in nation1.towns) {
                for (town2 in nation2.towns) {
                    town1.enemies.add(town2)
                    town2.enemies.add(town1)
                }
            }
        }

        // apply alliances between towns in nations
        // for ( nation in Nodes.nations.values ) {
        //     for ( town1 in nation.towns ) {
        //         for ( town2 in nation.towns ) {
        //             if ( town1 !== town2 ) {

        //             }
        //         }
        //     }
        // }
    }

//    // ==============================================
//    // Resource Node functions
//    // ==============================================
//
    // return number of resource node types
fun getResourceNodeCount(): Int = resourceNodes.size

    // ==============================================
    // Territory Chunk functions
    // ==============================================
    fun getTerritoryChunkFromBlock(blockX: Int, blockZ: Int): TerritoryChunk? {
        val coord = Coord.fromBlockCoords(blockX, blockZ)
        return territoryChunks.get(coord)
    }

    fun getTerritoryChunkFromCoord(coord: Coord): TerritoryChunk? = territoryChunks.get(coord)

    // ==============================================
    // Territory functions
    // ==============================================

    // return number of territories in world
    fun getTerritoryCount(): Int = territories.size

    fun getTerritoryFromId(id: TerritoryId): Territory? = territories.get(id)

    fun getTerritoryFromBlock(blockX: Int, blockZ: Int): Territory? {
        val coord = Coord.fromBlockCoords(blockX, blockZ)
        return territoryChunks.get(coord)?.territory
    }

    fun getTerritoryFromPlayer(player: Player): Territory? {
        val loc = player.position
        val coord = Coord.fromBlockCoords(loc.x.toInt(), loc.z.toInt())
        return territoryChunks.get(coord)?.territory
    }

    fun getTerritoryFromCoord(coord: Coord): Territory? = territoryChunks.get(coord)?.territory

    fun getTerritoryFromChunk(chunk: Chunk): Territory? {
        val coord = Coord(chunk.chunkX, chunk.chunkZ)
        return territoryChunks.get(coord)?.territory
    }

    fun getTerritoryFromChunkCoords(cx: Int, cz: Int): Territory? {
        val coord = Coord(cx, cz)
        return territoryChunks.get(coord)?.territory
    }

//    /**
//     * Returns an iterable of all (terrId, territory) pairs in world.
//     */
//    public fun iterTerritories(): Iterable<kotlin.collections.Map.Entry<TerritoryId, Territory>> = Nodes.territories.asIterable()
//
//    public fun getChunkFromCoord(coord: Coord, world: World): Chunk? = Bukkit.getWorld(world.name)?.getChunkAt(coord.x, coord.z)

    // default spawn location: returns region ~center of
    // core chunk of home territory
    // y-level is first empty air block
    fun getDefaultSpawnLocation(territory: Territory): Pos {
        // get from ~middle of territory home chunk
        val homeChunk = territory.core
        val x = homeChunk.x * 16 + 8
        val z = homeChunk.z * 16 + 8

        // iterate up in y to find first empty block
        var y = 255
        try {
            while (y > 0) {
                if (MinecraftServer.getInstanceManager().instances.first().getBlock(x, y, z).isAir) {
                    break
                }
                y -= 1
            }
        } catch (_: NullPointerException) {
            // chunk isnt loaded
        }

        return Pos(x.toDouble(), y.toDouble(), z.toDouble())
    }

    // ==============================================
    // Resident functions
    // ==============================================
    fun createResident(player: Player) {
        val uuid = player.uuid
        if (!residents.containsKey(uuid)) {
            val resident = Resident(uuid, player.username)
            residents.put(uuid, resident)

            needsSave = true
        }
    }

    // loads a resident from UUID and other parameters
    // used for deserializing from towns.json
    fun loadResident(
        uuid: UUID,
        name: String,
        prefix: String,
        suffix: String,
        trusted: Boolean,
        townCreateCooldown: Long,
    ) {
        // create and add resident
        val resident = Resident(uuid, name)
        resident.prefix = prefix
        resident.suffix = suffix

        // resident trusted status
        resident.trusted = trusted

        // mark resident needs update
        resident.needsUpdate()

        // set resident town create cooldown
        resident.townCreateCooldown = townCreateCooldown

        residents.put(uuid, resident)
    }

    fun getResidentCount(): Int = residents.size

    fun getResident(player: Player): Resident? = residents.get(player.uuid)

    fun getResidentFromName(name: String): Resident? {
        // get player from server
        val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name)
        if (player != null) {
            return residents.get(player.uuid)
        }
        // search through residents and try to match name
        else {
            val playerNameLowercase = name.lowercase()
            for (r in residents.values) {
                if (r.name.lowercase() == playerNameLowercase) {
                    return r
                }
            }
        }

        return null
    }

    fun getResidentFromUUID(uuid: UUID): Resident? = residents.get(uuid)

    fun setResidentPrefix(resident: Resident, s: String) {
        resident.prefix = sanitizeString(s)
        resident.needsUpdate()
        needsSave = true
    }

    fun setResidentSuffix(resident: Resident, s: String) {
        resident.suffix = sanitizeString(s)
        resident.needsUpdate()
        needsSave = true
    }

    // marks player as online
    fun setResidentOnline(resident: Resident, player: Player) {
        val town = resident.town
        if (town != null) {
            town.playersOnline.add(player)

            val nation = town.nation
            nation?.playersOnline?.add(player)
        }
    }

    // marks player as offline
    // force player as input in case resident cannot access player
    // if player already offline
    fun setResidentOffline(resident: Resident, player: Player) {
        val town = resident.town
        if (town != null) {
            town.playersOnline.remove(player)

            val nation = town.nation
            nation?.playersOnline?.remove(player)
        }
    }
//
//    // toggle chat mode:
//    // if already in mode, return to default (global)
//    // else, set to new mode
//    // return chatmode this is set to
//    public fun toggleChatMode(resident: Resident, mode: ChatMode): ChatMode {
//        if (resident.chatMode == mode) {
//            resident.chatMode = ChatMode.GLOBAL
//        } else {
//            resident.chatMode = mode
//        }
//
//        return resident.chatMode
//    }

    // set player's town create cooldown
    fun setResidentTownCreateCooldown(resident: Resident, cooldown: Long) {
        resident.townCreateCooldown = cooldown
        resident.needsUpdate()
        needsSave = true
    }
//
//    // update players online in each town, nation
//    public fun refreshPlayersOnline() {
//        // remove players online in nations
//        for (nation in Nodes.nations.values) {
//            nation.playersOnline.clear()
//        }
//
//        for (town in Nodes.towns.values) {
//            town.playersOnline.clear()
//            for (r in town.residents) {
//                val player = r.player()
//                if (player !== null) {
//                    town.playersOnline.add(player)
//
//                    val nation = r.nation
//                    if (nation !== null) {
//                        nation.playersOnline.add(player)
//                    }
//                }
//            }
//        }
//    }

    // forces re-render of online player minimaps
    fun renderMinimaps() {
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            val resident = getResident(player)
            if (resident?.minimap != null) {
                // get current location coord
                val loc = player.position
                val coordX = kotlin.math.floor(loc.x).toInt()
                val coordZ = kotlin.math.floor(loc.z).toInt()
                val coord = Coord.fromBlockCoords(coordX, coordZ)
                resident.updateMinimap(coord)
            }
        }
    }

    // ==============================================
    // Town functions
    // ==============================================

    // create town at player location:
    // - player becomes town leader
    // - territory at player's location becomes town home
    fun createTown(name: String, territory: Territory, leader: Resident?): Result<Town> {
        // get resident and spawn coordinate from leader if exists
        val leaderPlayer = leader?.player()
        val spawnpoint = if (leaderPlayer != null) {
            leaderPlayer.position
        } else {
            getDefaultSpawnLocation(territory)
        }

        if (getTownFromName(name) != null) {
            return Result.failure(ErrorTownExists)
        }

        if (territory.town != null) {
            return Result.failure(ErrorTerritoryOwned)
        }

        if (leader?.town != null) {
            return Result.failure(ErrorPlayerHasTown)
        }

        val town = Town(UUID.randomUUID(), name, territory.id, leader, spawnpoint)

        // set home territory town
        territory.town = town

        if (leader != null) {
            leader.town = town

            leader.needsUpdate()
        }

        towns.put(name, town)
        needsSave = true

        // re-render minimaps
        renderMinimaps()

        // update nametags
//        Nametag.pipelinedUpdateAllText()

        return Result.success(town)
    }

    // load town from data
    // used for deserializing from towns.json
    fun loadTown(
        uuid: UUID,
        name: String,
        leader: UUID?,
        homeId: Int,
        spawn: Pos?,
        color: Color?,
        residents: ArrayList<UUID>,
        officers: ArrayList<UUID>,
        territoryIds: ArrayList<Int>,
        capturedTerritoryIds: ArrayList<Int>,
        annexedTerritoryIds: ArrayList<Int>,
        income: MutableMap<Material, Int>,
        permissions: MutableMap<TownPermissions, EnumSet<PermissionsGroup>>,
        isOpen: Boolean,
//        protectedBlocks: HashSet<Block>,
        moveHomeCooldown: Long,
    ): Town? {
        val leaderAsResident = if (leader != null) {
            getResidentFromUUID(leader)
        } else {
            null
        }

        // make sure home territory exists
        val home = getTerritoryFromId(TerritoryId(homeId))
        if (home == null) {
            System.err.println("Failed to create town $name with home (id = $homeId)")
            return null
        }

        // get spawn point
        val spawnpoint = if (spawn != null) {
            spawn
        } else { // get default value
            val homeTerritory = territories[TerritoryId(homeId)]
            if (homeTerritory != null) {
                getDefaultSpawnLocation(homeTerritory)
            } else {
                Pos(0.0, 255.0, 0.0)
            }
        }

        val town = Town(uuid, name, home.id, leaderAsResident, spawnpoint)
        leaderAsResident?.town = town

        // add residents
        for (id in residents) {
            getResidentFromUUID(id)?.let { r ->
                town.residents.add(r)
                r.town = town
                r.needsUpdate()
            }
        }

        // add officers
        for (id in officers) {
            getResidentFromUUID(id)?.let { r ->
                town.officers.add(r)
            }
        }

        // add territory claims
        for (id in territoryIds) {
            val terrId = TerritoryId(id)
            getTerritoryFromId(terrId)?.let { terr ->
                town.territories.add(terrId)
                terr.town = town
            }
        }

        // add annexed territories
        // (duplicated in territoryIds, so just add ids)
        for (id in annexedTerritoryIds) {
            val terrId = TerritoryId(id)
            getTerritoryFromId(terrId)?.let { terr ->
                if (town.territories.contains(terrId)) {
                    town.annexed.add(terrId)
                }
            }
        }

        // add captured territories
        for (id in capturedTerritoryIds) {
            val terrId = TerritoryId(id)
            getTerritoryFromId(terrId)?.let { terr ->
                // check if territory already occupied, remove current occupier
                // should never occur, but just in case
                val currentOccupier: Town? = terr.occupier
                currentOccupier?.captured?.remove(terrId)

                // capture territory for town
                town.captured.add(terrId)
                terr.occupier = town
            }
        }

        // add saved income
        town.income.storage.putAll(income)

        // set town color
        if (color != null) {
            town.color = color
        }

        // add permission flags
        for ((type, groups) in permissions) {
            town.permissions[type].clear()
            town.permissions[type].addAll(groups)
        }

        // set isOpen
        town.isOpen = isOpen

//        // add protected blocks
//        town.protectedBlocks.addAll(protectedBlocks)

        // set move home cooldown
        town.moveHomeCooldown = moveHomeCooldown

        // save new town
        towns.put(name, town)

        // mark dirty
        town.needsUpdate()

        return town
    }

    fun destroyTown(town: Town) {
        // remove town links backwards from creation:

        // check if town last in nation, destroy nation first if last
        val nation = town.nation
        if (nation !== null) {
            if (nation.towns.size == 1) { // last town in nation
                destroyNation(nation)
            } else {
                removeTownFromNation(nation, town)
            }
        }

        // remove territory claim links
        town.territories.forEach { terrId ->
            getTerritoryFromId(terrId)?.town = null
        }

        // remove occupied territories
        town.captured.forEach { terrId ->
            getTerritoryFromId(terrId)?.occupier = null
        }

        // remove resident town links
        town.residents.forEach { r ->
            r.town = null
            r.nation = null
            r.needsUpdate()

            // remove resident nametag, and remove from nation players online list
            val player = r.player()
            if (player !== null) {
                if (nation !== null) {
                    nation.playersOnline.remove(player)
                }
            }
        }

        // remove all town alliances
        for (ally in town.allies) {
            ally.allies.remove(town)
            ally.needsUpdate()
        }

        // remove all town enemies
        for (enemy in town.enemies) {
            enemy.enemies.remove(town)
            enemy.needsUpdate()
        }

        // remove town from global
        towns.remove(town.name)

        needsSave = true

        // re-render minimaps
        renderMinimaps()
//
//        // update nametags
//        Nametag.pipelinedUpdateAllText()
    }

    fun getTownCount(): Int = towns.size

    fun getTownFromName(name: String): Town? = towns.get(name)

    fun getTownFromPlayer(player: Player): Town? {
        // get resident
        val resident = getResident(player)
        if (resident !== null) {
            return resident.town
        }

        return null
    }
//
//    /**
//     * If input is "*", return all towns. Otherwise, return list of
//     * single town.
//     */
//    public fun matchTowns(name: String): List<Town> {
//        val matchedTowns = ArrayList<Town>()
//
//        if (name == "*") {
//            matchedTowns.addAll(towns.values)
//        } else {
//            val town = getTownFromName(name)
//            if (town !== null) {
//                matchedTowns.add(town)
//            }
//        }
//
//        return matchedTowns
//    }
//
//    /**
//     * Return town that owns chunk if it exists
//     */
//    public fun getTownAtChunkCoord(cx: Int, cz: Int): Town? {
//        val terr = Nodes.getTerritoryFromChunkCoords(cx, cz)
//        if (terr !== null) {
//            return terr.town
//        }
//
//        return null
//    }

    /**
     * Claim territory for a town. Returns result with either Territory
     * if successful, or an TerritoryClaim error status.
     */
    fun claimTerritory(town: Town, territory: Territory): Result<Territory> {
        // check if territory already claimed
        if (territory.town != null) {
            return Result.failure(ErrorTerritoryHasClaim)
        }

        // check if territory is connected to town's existing territories
        // iterate this territory neighbors, check if any link to town
        var isNeighbor = false
        for (neighborId in territory.neighbors) {
            if (getTerritoryFromId(neighborId)?.town === town) {
                isNeighbor = true
                break
            }
        }
        if (!isNeighbor) {
            return Result.failure(ErrorTerritoryNotConnected)
        }

        // passed checks, add territory to town
        town.territories.add(territory.id)
        territory.town = town

        // mark dirty
        town.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

        return Result.success(territory)
    }

    fun unclaimTerritory(town: Town, territory: Territory): Result<Territory> {
        // check if town owns territory
        if (!town.territories.contains(territory.id)) {
            return Result.failure(ErrorTerritoryNotInTown)
        }

        // check if territory is town's home territory
        if (town.home == territory.id) {
            return Result.failure(ErrorTerritoryIsTownHome)
        }

        // passed checks, remove territory from town
        town.territories.remove(territory.id)
        territory.town = null

        if (town.annexed.contains(territory.id)) {
            town.annexed.remove(territory.id)
        }

        // mark dirty
        town.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

        return Result.success(territory)
    }
//
//    // adds a territory to town and bypasses standard claim checks
//    // (e.g. territory must be connected, ...)
//    // if successful, returns added territory
//    public fun addTerritoryToTown(town: Town, territory: Territory): Result<Territory> {
//        // check territory not already occupied
//        if (territory.town != null) {
//            return Result.failure(ErrorTerritoryOwned)
//        }
//
//        // add territory to town
//        town.territories.add(territory.id)
//        territory.town = town
//
//        // mark dirty
//        town.needsUpdate()
//        Nodes.needsSave = true
//
//        // re-render minimaps
//        Nodes.renderMinimaps()
//
//        return Result.success(territory)
//    }
//
    // makes territory occupied by town
fun captureTerritory(town: Town, territory: Territory) {
        // check if territory already occupied, remove current occupier
        val currentOccupier: Town? = territory.occupier
        if (currentOccupier != null) {
            currentOccupier.captured.remove(territory.id)
            territory.occupier = null

            currentOccupier.needsUpdate()
        }

        // handle capturing enemy territory
        if (territory.town != town) {
            town.captured.add(territory.id)
            territory.occupier = town
        }

        town.needsUpdate()
        needsSave = true

        // re-render minimaps
    renderMinimaps()
    }

    // release territory from town occupation
    fun releaseTerritory(territory: Territory) {
        // check if territory currently occupied, remove current occupier
        val currentOccupier: Town? = territory.occupier
        if (currentOccupier != null) {
            currentOccupier.captured.remove(territory.id)
            territory.occupier = null

            currentOccupier.needsUpdate()
            needsSave = true

            // re-render minimaps
            renderMinimaps()
        }
    }

    /**
     * Town annexes a territory:
     * - add to town's territories and town's annexed territories
     * Returns boolean on success
     */
    fun annexTerritory(town: Town, territory: Territory): Boolean {
        val occupier: Town? = territory.occupier
        if (occupier !== town) {
            return false
        }

        val oldTown = territory.town
        if (oldTown === town) {
            return false
        }

        // remove from old town
        if (oldTown !== null) {
            // check if this is their home territory
            if (territory.id == oldTown.home) {
                // can only annex home territory last
                if (oldTown.territories.size > 1) {
                    return false
                }

                // destroy town and broadcast
                destroyTown(oldTown)

                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Town ${oldTown.name} was completely annexed by ${town.name}")
            }
            // else, just a normal territory
            else {
                if (oldTown.annexed.contains(territory.id)) {
                    oldTown.annexed.remove(territory.id)
                }

                // remove territory
                oldTown.territories.remove(territory.id)

                oldTown.needsUpdate()
            }
        }

        // add territory to town and annexed territories
        town.territories.add(territory.id)
        town.annexed.add(territory.id)
        town.captured.remove(territory.id)

        // update territory
        territory.town = town
        territory.occupier = null

        town.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

        return true
    }

    // adds items to town's income
    // used by taxation events in occupied/captured territories
    // TODO: cleanup + rename this to "townIncomeAdd"
    fun addToIncome(town: Town, material: Material, amount: Int) {
        town.income.add(material, amount)
        town.needsUpdate()
        needsSave = true
    }

    /**
     * Removes items from town's income. If Material is null, removes all
     * items. If amount < 0, removes all items of that type.
     */
    fun townIncomeRemove(
        town: Town,
        material: Material?,
        amount: Int = -1,
    ) {
        town.income.pushToStorage(force = true) // push items to storage before removing

        if (material !== null) {
            if (amount >= 0) {
                val currAmount = town.income.storage[material] ?: 0
                if (currAmount > amount) {
                    town.income.storage.put(material, currAmount - amount)
                } else {
                    town.income.storage.remove(material)
                }
            } else { // remove all
                town.income.storage.remove(material)
            }
        } else {
            town.income.storage.clear()
        }
        town.needsUpdate()
        needsSave = true
    }

    fun setTownColor(town: Town, r: Int, g: Int, b: Int) {
        town.color = Color(r, g, b)
        town.needsUpdate()
        needsSave = true
    }

    fun setTownSpawn(town: Town, spawnpoint: Pos): Boolean {
        // enforce spawnpoint in town's home territory
        val territory = getTerritoryFromChunk(MinecraftServer.getInstanceManager().instances.first().getChunk(spawnpoint.chunkX(),spawnpoint.chunkX())!!)
        if (territory === null || territory.id != town.home) {
            return false
        }

        town.spawnpoint = spawnpoint
        town.needsUpdate()
        needsSave = true

        return true
    }

    fun addResidentToTown(town: Town, resident: Resident) {
        town.residents.add(resident)
        resident.town = town

        // initialize player as untrusted
        resident.trusted = false

        // add player to town online players
        val player = resident.player()
        if (player !== null) {
            town.playersOnline.add(player)
        }

        // add town nation to resident
        val nation = town.nation
        if (nation !== null) {
            resident.nation = nation
            nation.residents.add(resident)

            // add player to nation online players
            if (player !== null) {
                nation.playersOnline.add(player)
            }
        }

        town.needsUpdate()
        resident.needsUpdate()
        needsSave = true

        // update nametags
//        Nametag.pipelinedUpdateAllText()
    }

    fun removeResidentFromTown(town: Town, resident: Resident) {
        if (town.officers.contains(resident)) {
            town.officers.remove(resident)
        }
        town.residents.remove(resident)
        resident.town = null

        val player = resident.player()

        // remove town nation from resident
        val nation = town.nation
        if (nation !== null) {
            resident.nation = null
            nation.residents.remove(resident)

            // remove player from nation online players
            if (player !== null) {
                nation.playersOnline.remove(player)
            }
        }

        // remove player to town online players
        if (player !== null) {
            town.playersOnline.remove(player)
        }

        town.needsUpdate()
        resident.needsUpdate()
        needsSave = true

        // update nametags
//        Nametag.pipelinedUpdateAllText()
    }

    // make player in town an officer
    fun townAddOfficer(town: Town, resident: Resident): Boolean {
        if (resident.town !== town) {
            return false
        }

        if (town.officers.contains(resident)) {
            return true
        }

        town.officers.add(resident)

        town.needsUpdate()
        needsSave = true

        return true
    }

    // remove player from officer status
    fun townRemoveOfficer(town: Town, resident: Resident): Boolean {
        if (resident.town !== town) {
            return false
        }

        town.officers.remove(resident)

        town.needsUpdate()
        needsSave = true

        return true
    }

//    public fun playerIsOfficer(town: Town, player: Player): Boolean {
//        val resident = Nodes.getResident(player)
//        if (resident === town.leader || town.officers.contains(resident)) {
//            return true
//        }
//        return false
//    }

    /**
     * Set town's leader. If input resident is null, try to remove
     * current town leader.
     */
    fun townSetLeader(town: Town, resident: Resident?) {
        if (resident !== null) {
            if (resident.town !== town) {
                // must first be part of town, skipping
                return
            }

            // same town leader, ignore
            if (town.leader === resident) {
                return
            }

            // remove resident from officers if there
            town.officers.remove(resident)

            town.leader = resident
            town.needsUpdate()
            needsSave = true
        } else {
            // no resident input: remove current town leader
            if (town.leader === null) {
                // no leader to remove, skipping
                return
            }

            town.leader = null
            town.needsUpdate()
            needsSave = true
        }
    }

    fun renameTown(town: Town, s: String): Boolean {
        // check that new name not used
        if (towns.contains(s)) {
            return false
        }

        towns.remove(town.name)
        town.name = s
        town.updateNametags()

        towns.put(s, town)
        town.needsUpdate()

        // update residents in town and nation
        town.nation?.needsUpdate()
        for (r in town.residents) {
            r.needsUpdate()
        }

        // update town allies/enemies
        for (t in town.enemies) {
            t.needsUpdate()
        }
        for (t in town.allies) {
            t.needsUpdate()
        }

        needsSave = true

        return true
    }

    // view town income inventory gui
    fun getTownIncomeInventory(town: Town): Inventory {
        // mark dirty if inventory not empty (player could take items)
        if (!town.income.empty()) {
            town.needsUpdate()
        }
        return town.income.getInventory()
    }

    /**
     * Set town permissions
     */
    fun setTownPermissions(town: Town, perm: TownPermissions, group: PermissionsGroup, flag: Boolean) {
        // add perms
        if (flag) {
            town.permissions[perm].add(group)
        } else { // remove perms
            town.permissions[perm].remove(group)
        }

        town.needsUpdate()
        needsSave = true
    }

    /**
     * set town's home territory
     */
    fun setTownHomeTerritory(town: Town, territory: Territory) {
        if (town !== territory.town) {
            return
        }
        if (town.home == territory.id) {
            return
        }

        // set town home
        town.home = territory.id

        // set town spawn to new home territory
        town.spawnpoint = getDefaultSpawnLocation(territory)

        // set cooldown
        town.moveHomeCooldown = config.townMoveHomeCooldown

        // re-render minimaps
        renderMinimaps()

        town.needsUpdate()
        needsSave = true
    }

    // set town's move home cooldown period
    fun setTownHomeMoveCooldown(town: Town, time: Long) {
        town.moveHomeCooldown = time
        town.needsUpdate()
        needsSave = true
    }

    // when inventory close, require save because items could have
    // been moved
    internal fun onTownIncomeInventoryClose() {
        needsSave = true
    }
//
//    // set town's isOpen state
//    internal fun setTownOpen(town: Town, isOpen: Boolean) {
//        town.isOpen = isOpen
//        town.needsUpdate()
//        Nodes.needsSave = true
//    }
//
    /**
     * Run cooldown tick, with change in time dt
     */
    internal fun townMoveHomeCooldownTick(dt: Long) {
        // reduce town move home territory cooldown
        for (town in towns.values) {
            if (town.moveHomeCooldown > 0) {
                town.moveHomeCooldown = (town.moveHomeCooldown - dt).coerceAtLeast(0)

                town.needsUpdate()
                needsSave = true
            }
        }
    }

    /**
     * Reduce resident town create cooldown tick by dt
     */
    internal fun residentTownCreateCooldownTick(dt: Long) {
        // reduce player create town cooldown
        for (resident in residents.values) {
            if (resident.townCreateCooldown > 0) {
                resident.townCreateCooldown = (resident.townCreateCooldown - dt).coerceAtLeast(0)

                resident.needsUpdate()
                needsSave = true
            }
        }
    }

    // ==============================================
    // Nation functions
    // ==============================================
    fun createNation(name: String, town: Town, leader: Resident? = null): Result<Nation> {
        if (town.nation != null) {
            return Result.failure(ErrorTownHasNation)
        }

        if (leader?.nation != null) {
            return Result.failure(ErrorPlayerHasNation)
        }

        if (leader != null && !town.residents.contains(leader)) {
            return Result.failure(ErrorPlayerNotInTown)
        }

        if (getNationFromName(name) != null) {
            return Result.failure(ErrorNationExists)
        }

        val nation = Nation(UUID.randomUUID(), name, town)
        nations.put(name, nation)

        // add town to nation
        nation.towns.add(town)
        town.nation = nation

        // remove all pre-existing town alliances, but do not change enemies
        for (ally in town.allies) {
            ally.allies.remove(town)
            ally.needsUpdate()
        }
        town.allies.clear()

        // add nation to all residents in creator's town
        for (r in town.residents) {
            r.nation = nation
            r.needsUpdate()
        }

        town.needsUpdate()
        nation.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

        // update nametags
//        Nametag.pipelinedUpdateAllText()

        return Result.success(nation)
    }

    // load nation from data
    // used for deserializing from towns.json
    fun loadNation(
        uuid: UUID,
        name: String,
        capitalName: String, // name of capital city
        color: Color?,
        towns: ArrayList<String>,
    ): Nation {
        val capital = getTownFromName(capitalName)
        if (capital == null) {
            throw ErrorTownDoesNotExist
        }

        val nation = Nation(uuid, name, capital)

        // set nation color
        if (color != null) {
            nation.color = color
        }

        // add towns
        for (townName in towns) {
            val town = getTownFromName(townName)
            if (town != null) {
                nation.towns.add(town)
                town.nation = nation
                town.needsUpdate()

                for (r in town.residents) {
                    r.nation = nation
                    nation.residents.add(r)
                    r.needsUpdate()
                }
            }
        }

        // mark dirty
        nation.needsUpdate()

        // save new nation
        nations.put(name, nation)

        return nation
    }

    fun destroyNation(nation: Nation) {
        // remove nation level alliances and enemies
        for (ally in nation.allies) {
            ally.allies.remove(nation)
            ally.needsUpdate()
        }
        for (enemy in nation.enemies) {
            enemy.enemies.remove(nation)
            enemy.needsUpdate()
        }

        // remove town links
        for (town in nation.towns) {
            // remove all residents links
            for (r in town.residents) {
                r.nation = null
                r.needsUpdate()
            }

            // remove all town alliances and enemies
            for (ally in town.allies) {
                ally.allies.remove(town)
                ally.needsUpdate()
            }
            town.allies.clear()

            for (enemy in town.enemies) {
                enemy.enemies.remove(town)
                enemy.needsUpdate()
            }
            town.enemies.clear()

            town.nation = null
            town.needsUpdate()
        }

        nations.remove(nation.name)

        needsSave = true

        // re-render minimaps
        renderMinimaps()
//
//        // update nametags
//        Nametag.pipelinedUpdateAllText()
    }

    fun getNationCount(): Int = nations.size

    fun getNationFromName(name: String): Nation? = nations.get(name)

    fun addTownToNation(nation: Nation, town: Town): Result<Town> {
        // check town does not belong to nation
        if (town.nation != null) {
            return Result.failure(ErrorTownHasNation)
        }

        // add town to nation
        nation.towns.add(town)
        town.nation = nation
        town.needsUpdate()

        // add nation to residents
        for (r in town.residents) {
            r.nation = nation
            nation.residents.add(r)
            val player = r.player()
            if (player !== null) {
                nation.playersOnline.add(player)
            }
            r.needsUpdate()
        }

        // remove current town alliances and enemies, set equal to nation capital
        for (ally in town.allies) {
            ally.allies.remove(town)
            ally.needsUpdate()
        }
        for (enemy in town.enemies) {
            enemy.enemies.remove(town)
            enemy.needsUpdate()
        }

        town.allies.clear()
        town.enemies.clear()

        for (ally in nation.capital.allies) {
            town.allies.add(ally)
            ally.allies.add(town)
            ally.needsUpdate()
        }
        for (enemy in nation.capital.enemies) {
            town.enemies.add(enemy)
            enemy.enemies.add(town)
            enemy.needsUpdate()
        }

        nation.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

        // update nametags
//        Nametag.pipelinedUpdateAllText()

        return Result.success(town)
    }

    fun removeTownFromNation(nation: Nation, town: Town): Result<Town> {
        // check town belongs to nation
        if (town.nation !== nation) {
            return Result.failure(ErrorNationDoesNotHaveTown)
        }

        // remove town to nation
        nation.towns.remove(town)
        town.nation = null

        // remove nation from residents
        for (r in town.residents) {
            r.nation = null
            nation.residents.remove(r)
            r.needsUpdate()
        }

        // if nation has no more towns, destroy nation
        // -> cleans up allies, enemies, etc... and global references
        if (nation.towns.isEmpty()) {
            destroyNation(nation)
        }
        // set new leader for nation if this was capital
        else {
            if (town === nation.capital) {
                val newCapital: Town = nation.towns.first()
                nation.capital = newCapital
                // print message to players in new capital
                for (r in newCapital.residents) {
                    val player = r.player()
                    if (player !== null) {
                        Message.print(player, "Your town is now the capital of ${nation.name}")
                    }
                }
            }
        }

        // remove all town alliances and enemies
        for (ally in town.allies) {
            ally.allies.remove(town)
            ally.needsUpdate()
        }
        town.allies.clear()

        for (enemy in town.enemies) {
            enemy.enemies.remove(town)
            enemy.needsUpdate()
        }
        town.enemies.clear()

        town.needsUpdate()
        nation.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()
//
//        // update nametags
//        Nametag.pipelinedUpdateAllText()

        return Result.success(town)
    }

    fun setNationColor(nation: Nation, r: Int, g: Int, b: Int) {
        nation.color = Color(r, g, b)
        nation.needsUpdate()
        needsSave = true
    }

    fun renameNation(nation: Nation, s: String): Boolean {
        // check that new name not used
        if (nations.contains(s)) {
            return false
        }

        nations.remove(nation.name)
        nation.name = s
        nations.put(s, nation)
        nation.needsUpdate()

        // update nation towns and residents
        for (town in nation.towns) {
            town.needsUpdate()
            for (r in town.residents) {
                r.needsUpdate()
            }
        }

        // update nation allies/enemies
        for (n in nation.enemies) {
            n.needsUpdate()
        }
        for (n in nation.allies) {
            n.needsUpdate()
        }

        needsSave = true

        return true
    }

    /**
     * Set nation capital to a new town. Town must be in the nation already
     */
    fun setNationCapital(nation: Nation, town: Town) {
        if (town.nation !== nation || nation.capital === town) {
            return
        }

        nation.capital = town

        nation.needsUpdate()
        needsSave = true
    }

    // ==============================================
    // Territory income cycle functions
    // ==============================================

    /**
     * System to run income from all town territories and deposit items into
     * a town's income inventory chest. For a town's occupied territories,
     * it gives the income to the occupier town.
     * Strategy for income:
     *     for each town:
     *         // 1. construct hashmap of each town name mapped to an enum map of
     *         //    material mapped to net income to be given
     *         townIncomes = HashMap<Town, EnumMap<Material, Double>>
     *
     *         // 2. accumulate net income from each territory into the town
     *         //    incomes hashmap. for occupied territories, create a new
     *         //    town entry if it doesn't exist.
     *         for territory in town:
     *             doTownIncomeLogic(territory)
     *
     *         // 3. add incomes to each town's income chests
     *         townIncomes.forEach { town, income -> addTownIncomeToChest(town, income) }
     */
    fun runIncome() {
        /**
         * Helper to convert an income item rate Double to a item count as Int.
         * The rate must be >0.0 but can have fractional parts which allow for
         * random rolls for item amount. Examples for handling rates:
         * - rate = 2.0 : 2 items
         * - rate = 2.5 : split into 2.0 + 0.5
         *      - 2.0 -> 2 items are guaranteed
         *      - 0.5 -> do random roll, if roll < 0.5, add 1 item (50% chance)
         */
        fun rateToAmount(rate: Double): Int {
            if (rate <= 0.0) {
                return 0
            }

            // determine integer part and fractional remainder for random roll
            val intPart = kotlin.math.floor(rate)
            val fracPart = kotlin.math.max(0.0, rate - intPart)

            val fracAmount = if (fracPart > 0.0) {
                val roll = ThreadLocalRandom.current().nextDouble()
                if (roll < fracPart) {
                    1
                } else {
                    0
                }
            } else {
                0
            }

            return intPart.toInt() + fracAmount
        }

        // tax and kept item rates for occupied territories
        val taxRate = config.taxIncomeRate.coerceIn(0.0, 1.0)
        val keptRate = 1.0 - taxRate

        for (town in towns.values) {
            try {
                val thisTownIncome = mutableMapOf<Material, Double>() // hard-coded value for this town
                val townIncomes = HashMap<Town, MutableMap<Material, Double>>()

                // inject this town
                townIncomes[town] = thisTownIncome

                for (terrId in town.territories) {
                    val territory = getTerritoryFromId(terrId)
                    if (territory === null) {
                        continue
                    }

                    val occupier = territory.occupier
                    if (occupier != null) {
                        val occupierIncome = townIncomes.getOrPut(occupier) { mutableMapOf() }

                        // regular item income
                        for ((material, amount) in territory.income) {
                            occupierIncome[material] = (occupierIncome[material] ?: 0.0) + (amount * taxRate)
                            thisTownIncome[material] = (thisTownIncome[material] ?: 0.0) + (amount * keptRate)
                        }
                    } else {
                        // regular item income
                        for ((material, amount) in territory.income) {
                            thisTownIncome[material] = (thisTownIncome[material] ?: 0.0) + amount
                        }
                    }
                }

                // apply income modifiers for each town, then add items to town income chest
                for ((_, income) in townIncomes) {
                    val incomeModifier = 1.0

                    // we can do any other income modifiers here in the future

                    // add items to town income chest
                    for ((material, amount) in income) {
                        val amountInt = rateToAmount(amount * incomeModifier)
                        if (amountInt > 0) {
                            addToIncome(town, material, amountInt)
                        }
                    }
                }
            } catch (err: Exception) {
                println("Error running income for town ${town.name}")
                err.printStackTrace()
            }
        }

        // message players ingame that income collected
        Message.broadcast("Towns have collected income (use \"/t income\" to get)")
    }

//    // ==============================================
//    // Handle war and diplomatic relations
//    //
//    // Rules for declaring war:
//    // 1. who can declare war:
//    //    - town without nation: town
//    //    - town with nation:
//    //       - capital town declare war against other towns
//    //       - internal town declare war on other towns in nation
//    //    - cannot war an ally
//    // 2. war on enemy town:
//    //    - if enemy town has no nation, only war against town
//    //    - if enemy town has nation, war defaults against enemy nation
//    //    - if enemy town is in your nation, civil war boogaloo
//    // 3. war on enemy nation:
//    //    - all towns in enemy nation become enemies of all
//    //      towns in your nation
//    //
//    // Same rules apply for making allies.
//    // ==============================================

    fun enableWar(
        canAnnexTerritories: Boolean,
        canOnlyAttackBorders: Boolean,
        destructionEnabled: Boolean,
    ) {
        war.enable(canAnnexTerritories, canOnlyAttackBorders, destructionEnabled)
    }

    fun disableWar() {
        war.disable()

        // re-render minimaps
        renderMinimaps()
    }

    /**
     * Set two towns as enemies and their nations if needed, order does not matter.
     * This should be the main function used.
     */
    fun addEnemy(town: Town, enemy: Town): Result<Boolean> {
        // make sure towns are not allies
        if (town.allies.contains(enemy)) {
            return Result.failure(ErrorWarAlly)
        }

        // check if towns already enemies
        if (town.enemies.contains(enemy) || enemy.enemies.contains(town)) {
            return Result.failure(ErrorAlreadyEnemies)
        }

        val townNation = town.nation
        val enemyNation = enemy.nation

        if (townNation !== null) {
            // nation-nation war
            if (enemyNation !== null) {
                // civil war boogaloo
                if (enemyNation === townNation) {
                    town.enemies.add(enemy)
                    enemy.enemies.add(town)

                    // remove ally status
                    town.allies.remove(enemy)
                    enemy.allies.remove(town)
                }
                // default to nation-nation war
                else {
                    if (town === townNation.capital) { // only nation leading town declare war
                        return nationAddEnemy(townNation, enemyNation)
                    }
                }
            }
            // nation-town war
            else {
                for (t in townNation.towns) {
                    t.enemies.add(enemy)
                    enemy.enemies.add(t)

                    t.needsUpdate()
                }
            }
        }
        // town declaring war without nation
        else {
            // town-nation war
            if (enemyNation !== null) {
                for (t in enemyNation.towns) {
                    t.enemies.add(town)
                    town.enemies.add(t)

                    t.needsUpdate()
                }
            }
            // town-town war
            else {
                town.enemies.add(enemy)
                enemy.enemies.add(town)
            }
        }

        town.needsUpdate()
        enemy.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

        // update nametags
//        Nametag.pipelinedUpdateAllText()

        return Result.success(true)
    }

//    /**
//     * Removes enemy status between two towns (and nations if needed)
//     * Order does not matter.
//     */
//    public fun removeEnemy(town: Town, enemy: Town): Result<Boolean> {
//        val townNation = town.nation
//        val enemyNation = enemy.nation
//
//        if (townNation !== null) {
//            // nation-nation war
//            if (enemyNation !== null) {
//                // civil war boogaloo
//                if (enemyNation === townNation) {
//                    town.enemies.remove(enemy)
//                    enemy.enemies.remove(town)
//
//                    town.allies.add(enemy)
//                    enemy.allies.add(town)
//                }
//                // default to nation-nation war
//                else {
//                    return nationRemoveEnemy(townNation, enemyNation)
//                }
//            }
//            // nation-town war
//            else {
//                for (t in townNation.towns) {
//                    t.enemies.remove(enemy)
//                    enemy.enemies.remove(t)
//
//                    t.needsUpdate()
//                }
//            }
//        }
//        // town declaring war without nation
//        else {
//            // town-nation war
//            if (enemyNation !== null) {
//                for (t in enemyNation.towns) {
//                    t.enemies.remove(town)
//                    town.enemies.remove(t)
//
//                    t.needsUpdate()
//                }
//            }
//            // town-town war
//            else {
//                town.enemies.remove(enemy)
//                enemy.enemies.remove(town)
//            }
//        }
//
//        town.needsUpdate()
//        enemy.needsUpdate()
//        Nodes.needsSave = true
//
//        // re-render minimaps
//        Nodes.renderMinimaps()
//
//        // update nametags
//        Nametag.pipelinedUpdateAllText()
//
//        return Result.success(true)
//    }
//
    /**
     * Set two towns as allies (bidirectional), order does not matter.
     * Handle town-town, town-nation, nation-town, nation-nation cases
     */
    fun addAlly(town: Town, other: Town): Result<Boolean> {
        // towns already allies
        if (town.allies.contains(other) && other.allies.contains(town)) {
            return Result.failure(ErrorAlreadyAllies)
        }

        // cannot ally enemies
        if (town.enemies.contains(other) || other.enemies.contains(town)) {
            return Result.failure(ErrorAlreadyEnemies)
        }

        val townNation = town.nation
        val otherNation = other.nation

        if (townNation !== null) {
            // nation-nation
            if (otherNation !== null && townNation !== otherNation) {
                nationAddAlly(townNation, otherNation)
            }
            // nation-town
            else {
                for (t in townNation.towns) {
                    t.allies.add(other)
                    other.allies.add(town)

                    val msgTown1 = "Your town is now allied with ${other.name}"
                    for (r in t.residents) {
                        val player = r.player()
                        if (player !== null) {
                            Message.print(player, msgTown1)
                        }
                    }

                    val msgTown2 = "Your town is now allied with ${t.name}"
                    for (r in other.residents) {
                        val player = r.player()
                        if (player !== null) {
                            Message.print(player, msgTown2)
                        }
                    }

                    t.needsUpdate()
                }
            }
        }
        // town allying without nation
        else {
            // town-nation
            if (otherNation !== null) {
                for (t in otherNation.towns) {
                    t.allies.add(town)
                    town.allies.add(t)

                    val msgTown1 = "Your town is now allied with ${t.name}"
                    for (r in town.residents) {
                        val player = r.player()
                        if (player !== null) {
                            Message.print(player, msgTown1)
                        }
                    }

                    val msgTown2 = "Your town is now allied with ${town.name}"
                    for (r in t.residents) {
                        val player = r.player()
                        if (player !== null) {
                            Message.print(player, msgTown2)
                        }
                    }

                    t.needsUpdate()
                }
            }
            // town-town
            else {
                town.allies.add(other)
                other.allies.add(town)

                val msgTown1 = "Your town is now allied with ${other.name}"
                for (r in town.residents) {
                    val player = r.player()
                    if (player !== null) {
                        Message.print(player, msgTown1)
                    }
                }

                val msgTown2 = "Your town is now allied with ${town.name}"
                for (r in other.residents) {
                    val player = r.player()
                    if (player !== null) {
                        Message.print(player, msgTown2)
                    }
                }
            }
        }

        town.needsUpdate()
        other.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

        // update nametags
//        Nametag.pipelinedUpdateAllText()

        return Result.success(true)
    }

    fun removeAlly(town: Town, other: Town): Result<Boolean> {
        // not currently allies
        if (!town.allies.contains(other) || !other.allies.contains(town)) {
            return Result.failure(ErrorNotAllies)
        }

        val townNation = town.nation
        val otherNation = other.nation

        if (townNation !== null) {
            // nation-nation
            if (otherNation !== null && townNation !== otherNation) {
                nationRemoveAlly(townNation, otherNation)
            }
            // nation-town
            else {
                for (t in townNation.towns) {
                    t.allies.remove(other)
                    other.allies.remove(town)

                    t.needsUpdate()
                }
            }
        }
        // town allying without nation
        else {
            // town-nation
            if (otherNation !== null) {
                for (t in otherNation.towns) {
                    t.allies.remove(town)
                    town.allies.remove(t)

                    t.needsUpdate()
                }
            }
            // town-town
            else {
                town.allies.remove(other)
                other.allies.remove(town)
            }
        }

        town.needsUpdate()
        other.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

        // update nametags
//        Nametag.pipelinedUpdateAllText()

        return Result.success(true)
    }

    // set two nations as enemies (bidirectional), order does not matter
    // -> sets all towns in each nation as enemies
    // returns true on success
    private fun nationAddEnemy(nation: Nation, enemy: Nation): Result<Boolean> {
        // make sure nations are not allies
        if (nation.allies.contains(enemy)) {
            return Result.failure(ErrorWarAlly)
        }

        // check if nations already enemies
        if (nation.enemies.contains(enemy) && enemy.enemies.contains(nation)) {
            return Result.failure(ErrorAlreadyEnemies)
        }

        nation.enemies.add(enemy)
        enemy.enemies.add(nation)

        // mark all towns in each nation as enemies
        for (nationTown in nation.towns) {
            for (enemyTown in enemy.towns) {
                nationTown.enemies.add(enemyTown)
                enemyTown.enemies.add(nationTown)

                enemyTown.needsUpdate()
            }
            nationTown.needsUpdate()
        }

        needsSave = true

        // re-render minimaps
        renderMinimaps()

        return Result.success(true)
    }

//    private fun nationRemoveEnemy(nation: Nation, enemy: Nation): Result<Boolean> {
//        nation.enemies.remove(enemy)
//        enemy.enemies.remove(nation)
//
//        // remove enemy status between towns in each nation
//        for (nationTown in nation.towns) {
//            for (enemyTown in enemy.towns) {
//                nationTown.enemies.remove(enemyTown)
//                enemyTown.enemies.remove(nationTown)
//
//                enemyTown.needsUpdate()
//            }
//            nationTown.needsUpdate()
//        }
//
//        Nodes.needsSave = true
//
//        // re-render minimaps
//        Nodes.renderMinimaps()
//
//        return Result.success(true)
//    }
//
    // add alliance between two nations and their towns (bidirectional)
    private fun nationAddAlly(nation: Nation, ally: Nation): Result<Boolean> {
        // make sure nations are not enemies
        if (nation.enemies.contains(ally) || ally.enemies.contains(nation)) {
            return Result.failure(ErrorAlreadyEnemies)
        }

        // check if nations already have alliance
        if (nation.allies.contains(ally) && ally.allies.contains(nation)) {
            return Result.failure(ErrorAlreadyAllies)
        }

        nation.allies.add(ally)
        ally.allies.add(nation)

        // mark all towns in each nation as enemies
        for (nationTown in nation.towns) {
            for (allyTown in ally.towns) {
                nationTown.allies.add(allyTown)
                allyTown.allies.add(nationTown)

                allyTown.needsUpdate()

                val msgTown1 = "Your town is now allied with ${allyTown.name}"
                for (r in nationTown.residents) {
                    val player = r.player()
                    if (player !== null) {
                        Message.print(player, msgTown1)
                    }
                }

                val msgTown2 = "Your town is now allied with ${nationTown.name}"
                for (r in allyTown.residents) {
                    val player = r.player()
                    if (player !== null) {
                        Message.print(player, msgTown2)
                    }
                }
            }
            nationTown.needsUpdate()
        }

        needsSave = true

        // re-render minimaps
    renderMinimaps()

        return Result.success(true)
    }

    private fun nationRemoveAlly(nation: Nation, ally: Nation): Result<Boolean> {
        nation.allies.remove(ally)
        ally.allies.remove(nation)

        // mark all towns in each nation as enemies
        for (nationTown in nation.towns) {
            for (allyTown in ally.towns) {
                nationTown.allies.remove(allyTown)
                allyTown.allies.remove(nationTown)

                allyTown.needsUpdate()
            }
            nationTown.needsUpdate()
        }

        needsSave = true

        // re-render minimaps
        renderMinimaps()

        return Result.success(true)
    }

    /**
     * Get diplomatic relationship between two towns
     */
    fun getRelationshipOfTownToTown(playerTown: Town?, otherTown: Town?): DiplomaticRelationship {
        if (playerTown !== null && otherTown !== null) {
            if (playerTown === otherTown) {
                return DiplomaticRelationship.TOWN
            }

            val playerNation = playerTown.nation
            val otherNation = otherTown.nation
            if (playerNation !== null && playerNation === otherNation) {
                return DiplomaticRelationship.NATION
            }

            if (playerTown.allies.contains(otherTown)) {
                return DiplomaticRelationship.ALLY
            }

            if (playerTown.enemies.contains(otherTown)) {
                return DiplomaticRelationship.ENEMY
            }
        }

        return DiplomaticRelationship.NEUTRAL
    }

    fun getRelationshipOfPlayerToTown(player: Player, otherTown: Town): DiplomaticRelationship {
        val playerTown = getTownFromPlayer(player)
        return getRelationshipOfTownToTown(playerTown, otherTown)
    }
//
//    public fun getRelationshipOfPlayerToPlayer(player: Player, other: Player): DiplomaticRelationship {
//        val playerTown = Nodes.getTownFromPlayer(player)
//        val otherTown = Nodes.getTownFromPlayer(other)
//        return getRelationshipOfTownToTown(playerTown, otherTown)
//    }
//
    // ==============================================
    // Chest protection functions
    // ==============================================

    /**
     * Sets resident trust
     */
    internal fun setResidentTrust(resident: Resident, trust: Boolean) {
        resident.trusted = trust
        resident.needsUpdate()
        needsSave = true
    }

//    /**
//     * Add event listener for protecting/unprotecting chests
//     * with mouse clicks
//     */
//    internal fun startProtectingChests(resident: Resident) {
//        val player = resident.player()
//        if (player === null) {
//            return
//        }
//
//        val town = resident.town
//        if (town === null) {
//            return
//        }
//
//        // check that resident is leader or officer
//        if (resident !== town.leader && !town.officers.contains(resident)) {
//            return
//        }
//
//        resident.isProtectingChests = true
//    }
//
//    /**
//     * Remove event listener for protecting chests
//     */
//    internal fun stopProtectingChests(resident: Resident) {
//        val player = resident.player()
//        if (player === null) {
//            return
//        }
//
//        // remove resident links
//        resident.isProtectingChests = false
//    }
//
//    /**
//     * Mark town chest as protected by town
//     * Handles checking for connected chests.
//     * Protect: true/false setting for protecting or unprotecting
//     */
//    internal fun protectTownChest(town: Town, block: Block, protect: Boolean) {
//        // get connected chest blocks
//        fun getConnectedBlocks(block: Block): List<Block> {
//            val type = block.type
//            if (type == Material.CHEST || type == Material.TRAPPED_CHEST) {
//                val blockState = block.getState()
//                if (blockState is Chest) {
//                    val chest = blockState as Chest
//                    val inventory = chest.getInventory()
//                    if (inventory is DoubleChestInventory) {
//                        val doubleChest = inventory.getHolder() as DoubleChest
//
//                        // get sides and add to blocks
//                        val leftSide: Block = (doubleChest.getLeftSide() as Chest).block
//                        val rightSide: Block = (doubleChest.getRightSide() as Chest).block
//
//                        return listOf(leftSide, rightSide)
//                    }
//                }
//            }
//
//            return listOf(block)
//        }
//
//        // adding protection: get connected blocks, else only use block
//        val blocks: List<Block> = if (protect == true) {
//            getConnectedBlocks(block)
//        } else {
//            listOf(block)
//        }
//
//        if (protect == true) {
//            for (block in blocks) {
//                town.protectedBlocks.add(block)
//            }
//        } else {
//            for (block in blocks) {
//                town.protectedBlocks.remove(block)
//            }
//        }
//
//        town.needsUpdate()
//        Nodes.needsSave = true
//    }
//
//    /**
//     * Generate particles at town's protected chests viewed by
//     * input resident
//     */
//    internal fun showProtectedChests(town: Town, resident: Resident) {
//        val player = resident.player()
//        if (player === null) {
//            return
//        }
//
//        val protectedBlocks = town.protectedBlocks
//
//        // create repeating event to spawn particles each second
//        val particle = Particle.HAPPY_VILLAGER
//        val particleCount = 3
//        val randomOffsetXZ = 0.05
//        val randomOffsetY = 0.1
//
//        val maxRuns = 10
//        var runCount = 0
//
//        var task: io.papermc.paper.threadedregions.scheduler.ScheduledTask? = null
//
//        val runnable = object : Runnable {
//            override fun run() {
//                player.scheduler.run(
//                    Nodes.plugin!!,
//                    { _ ->
//                        for (block in protectedBlocks) {
//                            // corners
//                            val location1 = Location(block.world, block.x.toDouble() + 0.1, block.y.toDouble() + 0.5, block.z.toDouble() + 0.1)
//                            val location2 = Location(block.world, block.x.toDouble() + 0.1, block.y.toDouble() + 0.5, block.z.toDouble() + 0.9)
//                            val location3 = Location(block.world, block.x.toDouble() + 0.9, block.y.toDouble() + 0.5, block.z.toDouble() + 0.1)
//                            val location4 = Location(block.world, block.x.toDouble() + 0.9, block.y.toDouble() + 0.5, block.z.toDouble() + 0.9)
//
//                            // centers
//                            val location5 = Location(block.world, block.x.toDouble() + 0.5, block.y.toDouble() + 0.5, block.z.toDouble())
//                            val location6 = Location(block.world, block.x.toDouble(), block.y.toDouble() + 0.5, block.z.toDouble() + 0.5)
//                            val location7 = Location(block.world, block.x.toDouble() + 0.5, block.y.toDouble() + 0.5, block.z.toDouble() + 1.0)
//                            val location8 = Location(block.world, block.x.toDouble() + 1.0, block.y.toDouble() + 0.5, block.z.toDouble() + 0.5)
//
//                            player.spawnParticle(particle, location1, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
//                            player.spawnParticle(particle, location2, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
//                            player.spawnParticle(particle, location3, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
//                            player.spawnParticle(particle, location4, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
//                            player.spawnParticle(particle, location5, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
//                            player.spawnParticle(particle, location6, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
//                            player.spawnParticle(particle, location7, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
//                            player.spawnParticle(particle, location8, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
//                        }
//
//                        runCount += 1
//                        if (runCount > maxRuns) {
//                            task?.cancel()
//                        }
//                    },
//                    null,
//                )
//            }
//        }
//
//        task = Bukkit.getAsyncScheduler().runAtFixedRate(
//            Nodes.plugin!!,
//            { _ -> runnable.run() },
//            1000,
//            1000,
//            TimeUnit.MILLISECONDS,
//        )
//    }

    // ==============================================
    // Port functions
    // ==============================================
    // load port from data
    // used for deserializing from ports.json
    fun loadPort(
        name: String,
        locX: Int,
        locZ: Int,
        groups: HashSet<PortGroup>,
        isPublic: Boolean,
    ): Port? {
        val port = Port(name, locX, locZ, groups, isPublic)

        // save new port
        ports.put(name, port)

        // for each port, map the chunk its in to it
        val chunk = listOf(locX.floorDiv(16), locZ.floorDiv(16))
        chunkToPort.put(chunk, port)

        // mark dirty
        port.needsUpdate()

        return port
    }

//    public fun destroyPort(port: Port) {
//        // remove from ports map
//        Nodes.ports.remove(port.name)
//
//        // remove chunk mappings
//        val chunk = listOf(Math.floorDiv(port.locX, 16), Math.floorDiv(port.locZ, 16))
//        chunkToPort.remove(chunk)
//
//        Nodes.needsSave = true
//    }
//
//    public fun getPortFromName(name: String): Port? = ports.get(name)
//
fun getPortGroupFromName(name: String): PortGroup? = portGroups.get(name)

    // load port group from data
    // used for deserializing from ports.json
    fun loadPortGroup(name: String): PortGroup {
        val portGroup = PortGroup(name)
        portGroups.put(name, portGroup)
        portGroup.needsUpdate()
        return portGroup
    }

    fun createPortGroup(name: String): Result<PortGroup> {
        if (portGroups.containsKey(name)) {
            return Result.failure(ErrorPortExists)
        }

        val portGroup = PortGroup(name)
        portGroups.put(name, portGroup)

        return Result.success(portGroup)
    }

//    public fun destroyPortGroup(portGroup: PortGroup) {
//        // remove from portGroups map
//        Nodes.portGroups.remove(portGroup.name)
//
//        Nodes.needsSave = true
//    }
//
//    public fun createPort(
//        name: String,
//        locX: Int,
//        locZ: Int,
//        groups: HashSet<PortGroup>,
//        isPublic: Boolean,
//    ): Result<Port> {
//        // check if port already exists
//        if (ports.containsKey(name)) {
//            return Result.failure(ErrorPortExists)
//        }
//
//        val port = Port(name, locX, locZ, groups, isPublic)
//
//        // save new port
//        ports.put(name, port)
//
//        // for each port, map the chunk its in to it
//        val chunk = listOf(locX.floorDiv(16), locZ.floorDiv(16))
//        chunkToPort.put(chunk, port)
//
//        // mark dirty
//        port.needsUpdate()
//        needsSave = true
//
//        return Result.success(port)
//    }
//
//    public fun addPortToGroup(port: Port, group: PortGroup): Result<Port> {
//        // check port is not already in this group
//        if (port.groups.contains(group)) {
//            return Result.failure(ErrorPortInGroup)
//        }
//
//        port.groups.add(group)
//        port.needsUpdate()
//        Nodes.needsSave = true
//
//        return Result.success(port)
//    }
//
//    public fun removePortFromGroup(port: Port, group: PortGroup) {
//        // check port is in group
//        if (!port.groups.contains(group)) {
//            return
//        }
//
//        port.groups.remove(group)
//        port.needsUpdate()
//        Nodes.needsSave = true
//    }
//
//    /**
//     * Get port owner based on who owns chunk
//     * If no owner or if port is public, return null
//     * If chunk is occupied, return occupier
//     * Else, return territory town (may be null)
//     */
//    public fun getPortOwner(port: Port): Town? {
//        if (port.isPublic) {
//            return null
//        }
//
//        val chunk = getTerritoryChunkFromCoord(Coord(port.chunkX, port.chunkZ))
//        if (chunk === null) {
//            return null
//        }
//
//        val occupier = chunk.occupier
//        if (occupier !== null) {
//            return occupier
//        }
//
//        return chunk.territory.town
//    }
//
//    /**
//     * Check if two ports share a group
//     */
//    fun sharePortGroups(port1: Port, port2: Port): Boolean = port1.groups.any { it in port2.groups }
}
