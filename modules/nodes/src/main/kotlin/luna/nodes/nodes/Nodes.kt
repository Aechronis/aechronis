/*
 * Nodes Engine/API
 */

package luna.nodes.nodes

import com.google.gson.JsonObject
import luna.nodes.nodes.chat.ChatMode
import luna.nodes.nodes.commands.AllyChatCommand
import luna.nodes.nodes.commands.AllyCommand
import luna.nodes.nodes.commands.GlobalChatCommand
import luna.nodes.nodes.commands.NationChatCommand
import luna.nodes.nodes.commands.NationCommand
import luna.nodes.nodes.commands.NodesAdminCommand
import luna.nodes.nodes.commands.PlayerCommand
import luna.nodes.nodes.commands.PortCommand
import luna.nodes.nodes.commands.TerritoryCommand
import luna.nodes.nodes.commands.TownChatCommand
import luna.nodes.nodes.commands.TownCommand
import luna.nodes.nodes.commands.UnallyCommand
import luna.nodes.nodes.constants.DiplomaticRelationship
import luna.nodes.nodes.constants.ErrorAlreadyAllies
import luna.nodes.nodes.constants.ErrorAlreadyEnemies
import luna.nodes.nodes.constants.ErrorNationDoesNotHaveTown
import luna.nodes.nodes.constants.ErrorNationExists
import luna.nodes.nodes.constants.ErrorNotAllies
import luna.nodes.nodes.constants.ErrorPlayerHasNation
import luna.nodes.nodes.constants.ErrorPlayerHasTown
import luna.nodes.nodes.constants.ErrorPlayerNotInTown
import luna.nodes.nodes.constants.ErrorPortExists
import luna.nodes.nodes.constants.ErrorPortInGroup
import luna.nodes.nodes.constants.ErrorTerritoryIsTownHome
import luna.nodes.nodes.constants.ErrorTerritoryNotInTown
import luna.nodes.nodes.constants.ErrorTerritoryOwned
import luna.nodes.nodes.constants.ErrorTownDoesNotExist
import luna.nodes.nodes.constants.ErrorTownExists
import luna.nodes.nodes.constants.ErrorTownHasNation
import luna.nodes.nodes.constants.ErrorWarAlly
import luna.nodes.nodes.constants.ErrorWarSameNation
import luna.nodes.nodes.constants.PermissionsGroup
import luna.nodes.nodes.constants.TownPermissions
import luna.nodes.nodes.listeners.NodesChatListener
import luna.nodes.nodes.listeners.NodesChestProtectionDestroyListener
import luna.nodes.nodes.listeners.NodesChestProtectionListener
import luna.nodes.nodes.listeners.NodesIncomeInventoryListener
import luna.nodes.nodes.listeners.NodesPlayerDamageListener
import luna.nodes.nodes.listeners.NodesPlayerJoinQuitListener
import luna.nodes.nodes.listeners.NodesPlayerMoveListener
import luna.nodes.nodes.listeners.NodesWorldListener
import luna.nodes.nodes.objects.Coord
import luna.nodes.nodes.objects.DefaultResourceAttributeLoader
import luna.nodes.nodes.objects.Nametag
import luna.nodes.nodes.objects.Nation
import luna.nodes.nodes.objects.NationPair
import luna.nodes.nodes.objects.OreBlockCache
import luna.nodes.nodes.objects.OreSampler
import luna.nodes.nodes.objects.Port
import luna.nodes.nodes.objects.PortGroup
import luna.nodes.nodes.objects.Resident
import luna.nodes.nodes.objects.ResourceNode
import luna.nodes.nodes.objects.Territory
import luna.nodes.nodes.objects.TerritoryChunk
import luna.nodes.nodes.objects.TerritoryId
import luna.nodes.nodes.objects.TerritoryPreprocessing
import luna.nodes.nodes.objects.TerritoryResources
import luna.nodes.nodes.objects.Town
import luna.nodes.nodes.serdes.Deserializer
import luna.nodes.nodes.tasks.IncomeManager
import luna.nodes.nodes.tasks.SaveManager
import luna.nodes.nodes.tasks.TaskSaveBackup
import luna.nodes.nodes.tasks.TaskSavePorts
import luna.nodes.nodes.tasks.TaskSaveWorld
import luna.nodes.nodes.utils.Color
import luna.nodes.nodes.utils.loadLongFromFile
import luna.nodes.nodes.utils.saveStringToFile
import luna.nodes.nodes.war.FlagWar
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.inventory.Inventory
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Files
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadLocalRandom
import kotlin.system.measureNanoTime

/**
 * Nodes container
 */
object Nodes {

    // event nodes for listeners
    val lowPriorityEventNode = EventNode.all("nodes-low-priority").setPriority(999)
    val eventNode = EventNode.all("nodes")
    val highPriorityEventNode = EventNode.all("nodes-high-priority").setPriority(-999)

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

    // map of player -> task for warping
    var playerWarpTasks: HashMap<Player, Task> = hashMapOf()

    // map chunk coords -> port, assumes one chunk only has 1 port
    var chunkToPort: HashMap<List<Int>, Port> = hashMapOf()

    // last time backup occurred: NOTE this is accessed async
    internal var lastBackupTime: Long = 0 // milliseconds

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
        println("Loading world from: $config.path")
        try {
            if (loadWorld()) { // successful load
                // print number of resource nodes and territories loaded
                println("- Resource Nodes: ${getResourceNodeCount()}")
                println("- Territories: ${getTerritoryCount()}")
                println("- Residents: ${getResidentCount()}")
                println("- Towns: ${getTownCount()}")
                println("- Nations: ${getNationCount()}")
            } else {
                println("Error loading world: Invalid world file at ${config.path}/${config.pathWorld}")
            }
        } catch (err: Exception) {
            err.printStackTrace()
            println("Error loading world: $err")
        }

        MinecraftServer.getGlobalEventHandler().addChild(lowPriorityEventNode)
        MinecraftServer.getGlobalEventHandler().addChild(eventNode)
        MinecraftServer.getGlobalEventHandler().addChild(highPriorityEventNode)

        // register listeners
        NodesChatListener.init()
        NodesChestProtectionListener.init()
        NodesChestProtectionDestroyListener.init()
        NodesIncomeInventoryListener.init()
        NodesPlayerDamageListener.init()
        NodesPlayerJoinQuitListener.init()
        NodesPlayerMoveListener.init()
        NodesWorldListener.init()

        // shutdown task
        MinecraftServer.getSchedulerManager().buildShutdownTask { cleanup() }

//    // register commands
        MinecraftServer.getCommandManager().register(TownCommand())
        MinecraftServer.getCommandManager().register(NationCommand())
        MinecraftServer.getCommandManager().register(NodesAdminCommand())
        MinecraftServer.getCommandManager().register(AllyCommand())
        MinecraftServer.getCommandManager().register(UnallyCommand())
        MinecraftServer.getCommandManager().register(GlobalChatCommand())
        MinecraftServer.getCommandManager().register(TownChatCommand())
        MinecraftServer.getCommandManager().register(NationChatCommand())
        MinecraftServer.getCommandManager().register(AllyChatCommand())
        MinecraftServer.getCommandManager().register(PlayerCommand())
        MinecraftServer.getCommandManager().register(TerritoryCommand())
        MinecraftServer.getCommandManager().register(PortCommand())

        // load current income tick
        val currTime = System.currentTimeMillis()
        lastBackupTime = loadLongFromFile(config.pathLastBackupTime) ?: currTime

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
        IncomeManager.stop()
        Nametag.stop()

        SaveManager.start(config.savePeriod)
        IncomeManager.start(config.incomePeriod)
        Nametag.start(config.nametagUpdatePeriod)
    }

    // mark all current players in game as online
    // needed to correctly mark online players after library is initialized
    internal fun initializeOnlinePlayers() {
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            // create resident for player if it does not exist
            createResident(player)

            // mark player online
            val resident = getResident(player)!!
            setResidentOnline(resident, player)
        }
    }

    // clean up any world details
    // run when server shutdown
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
        // load nation-level diplomacy from town data
        // towns inherit alliance/enemy status from their nation
        val nationAlliancePairs: HashSet<NationPair> = hashSetOf()
        val nationEnemyPairs: HashSet<NationPair> = hashSetOf()

        for ((i, town) in towns.withIndex()) {
            val allies = townAllies[i]
            val enemies = townEnemies[i]

            val townNation = town.nation

            if (townNation !== null && town === townNation.capital) {
                for (name in allies) {
                    val other = Nodes.towns.get(name)
                    if (other !== null) {
                        val otherNation = other.nation
                        // add nationpairs for allies
                        if (otherNation !== null && other === otherNation.capital) {
                            nationAlliancePairs.add(NationPair(townNation, otherNation))
                        }
                    }
                }

                for (name in enemies) {
                    val other = Nodes.towns.get(name)
                    if (other !== null) {
                        val otherNation = other.nation
                        // add nationpairs for enemies
                        if (otherNation !== null && other === otherNation.capital) {
                            nationEnemyPairs.add(NationPair(townNation, otherNation))
                        }
                    }
                }
            }
        }

        // apply nation ally pairs
        for (nationPair in nationAlliancePairs) {
            val nation1 = nationPair.nation1
            val nation2 = nationPair.nation2

            nation1.allies.add(nation2)
            nation2.allies.add(nation1)
        }

        // apply enemy pairs
        for (nationPair in nationEnemyPairs) {
            val nation1 = nationPair.nation1
            val nation2 = nationPair.nation2

            nation1.enemies.add(nation2)
            nation2.enemies.add(nation1)
        }
    }

    // ==============================================
    // Resource Node functions
    // ==============================================

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
        trusted: Boolean,
    ) {
        // create and add resident
        val resident = Resident(uuid, name)

        // resident trusted status
        resident.trusted = trusted

        // mark resident needs update
        resident.needsUpdate()

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

    // toggle chat mode:
    // if already in mode, return to default (global)
    // else, set to new mode
    // return chatmode this is set to
    fun toggleChatMode(resident: Resident, mode: ChatMode): ChatMode {
        if (resident.chatMode == mode) {
            resident.chatMode = ChatMode.GLOBAL
        } else {
            resident.chatMode = mode
        }

        return resident.chatMode
    }

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
        protectedBlocks: HashSet<BlockVec>,
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

        // add protected blocks
        town.protectedBlocks.addAll(protectedBlocks)

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

            // remove from nation players online list
            val player = r.player()
            if (player !== null) {
                if (nation !== null) {
                    nation.playersOnline.remove(player)
                }
            }
        }

        // remove town from global
        towns.remove(town.name)

        needsSave = true

        // re-render minimaps
        renderMinimaps()
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

    // adds a territory to town and bypasses standard claim checks
    // (e.g. territory must be connected, ...)
    // if successful, returns added territory
    fun addTerritoryToTown(town: Town, territory: Territory): Result<Territory> {
        // check territory not already occupied
        if (territory.town != null) {
            return Result.failure(ErrorTerritoryOwned)
        }

        // add territory to town
        town.territories.add(territory.id)
        territory.town = town

        // mark dirty
        town.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

        return Result.success(territory)
    }

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

    // adds items to town's income
    // used by taxation events in occupied/captured territories
    fun addToIncome(town: Town, material: Material, amount: Int) {
        town.income.add(material, amount)
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
        val territory = getTerritoryFromBlock(spawnpoint.blockX(), spawnpoint.blockZ())
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

        // re-render minimaps
        renderMinimaps()

        town.needsUpdate()
        needsSave = true
    }

    // when inventory close, require save because items could have
    // been moved
    internal fun onTownIncomeInventoryClose() {
        needsSave = true
    }

    // check if two towns are allied (same nation or nations are allied)
    fun areTownsAllied(town1: Town?, town2: Town?): Boolean {
        if (town1 === null || town2 === null) return false
        if (town1 === town2) return true
        val nation1 = town1.nation
        val nation2 = town2.nation
        if (nation1 !== null && nation1 === nation2) return true
        if (nation1 !== null && nation2 !== null && nation1.allies.contains(nation2)) return true
        return false
    }

    // check if two towns are enemies (nations are enemies)
    fun areTownsEnemies(town1: Town?, town2: Town?): Boolean {
        if (town1 === null || town2 === null) return false
        val nation1 = town1.nation
        val nation2 = town2.nation
        return nation1 !== null && nation2 !== null && nation1.enemies.contains(nation2)
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

            town.nation = null
            town.needsUpdate()
        }

        nations.remove(nation.name)

        needsSave = true

        // re-render minimaps
        renderMinimaps()
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

        nation.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

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

        town.needsUpdate()
        nation.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

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

    // ==============================================
    // Handle war and diplomatic relations
    // ==============================================

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
     * Add alliance between two nations
     */
    fun addAlly(nation: Nation, other: Nation): Result<Boolean> {
        // nations already allies
        if ((nation.allies.contains(other) && other.allies.contains(nation)) || nation === other) {
            return Result.failure(ErrorAlreadyAllies)
        }

        // cannot ally enemies
        if (nation.enemies.contains(other) || other.enemies.contains(nation)) {
            return Result.failure(ErrorAlreadyEnemies)
        }

        nation.allies.add(other)
        other.allies.add(nation)

        // message all towns in both nations
        val msgNation1 = "Your nation is now allied with ${other.name}"
        for (t in nation.towns) {
            for (r in t.residents) {
                val player = r.player()
                if (player !== null) {
                    Message.print(player, msgNation1)
                }
            }
            t.needsUpdate()
        }

        val msgNation2 = "Your nation is now allied with ${nation.name}"
        for (t in other.towns) {
            for (r in t.residents) {
                val player = r.player()
                if (player !== null) {
                    Message.print(player, msgNation2)
                }
            }
            t.needsUpdate()
        }

        nation.needsUpdate()
        other.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

        return Result.success(true)
    }

    /**
     * Remove alliance between two nations
     */
    fun removeAlly(nation: Nation, other: Nation): Result<Boolean> {
        // not currently allies
        if (!nation.allies.contains(other) || !other.allies.contains(nation)) {
            return Result.failure(ErrorNotAllies)
        }

        nation.allies.remove(other)
        other.allies.remove(nation)

        // mark towns as needing update
        for (t in nation.towns) {
            t.needsUpdate()
        }
        for (t in other.towns) {
            t.needsUpdate()
        }

        nation.needsUpdate()
        other.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

        return Result.success(true)
    }

    // set two nations as enemies (bidirectional), order does not matter
    // returns true on success
    fun addEnemy(nation: Nation, enemy: Nation): Result<Boolean> {
        if (nation === enemy) {
            return Result.failure(ErrorWarSameNation)
        }

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

        // mark all towns as needing update (towns inherit enemy status from nation)
        for (nationTown in nation.towns) {
            nationTown.needsUpdate()
        }
        for (enemyTown in enemy.towns) {
            enemyTown.needsUpdate()
        }

        nation.needsUpdate()
        enemy.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

        return Result.success(true)
    }

    fun removeEnemy(nation: Nation, enemy: Nation): Result<Boolean> {
        nation.enemies.remove(enemy)
        enemy.enemies.remove(nation)

        // mark all towns as needing update (towns inherit enemy status from nation)
        for (nationTown in nation.towns) {
            nationTown.needsUpdate()
        }
        for (enemyTown in enemy.towns) {
            enemyTown.needsUpdate()
        }

        nation.needsUpdate()
        enemy.needsUpdate()
        needsSave = true

        // re-render minimaps
        renderMinimaps()

        return Result.success(true)
    }

    /**
     * Get diplomatic relationship between two towns.
     * Alliances and enemies are inherited from nations.
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

            // check nation-level alliances
            if (playerNation !== null && otherNation !== null) {
                if (playerNation.allies.contains(otherNation)) {
                    return DiplomaticRelationship.ALLY
                }

                if (playerNation.enemies.contains(otherNation)) {
                    return DiplomaticRelationship.ENEMY
                }
            }
        }

        return DiplomaticRelationship.NEUTRAL
    }

    fun getRelationshipOfPlayerToTown(player: Player, otherTown: Town): DiplomaticRelationship {
        val playerTown = getTownFromPlayer(player)
        return getRelationshipOfTownToTown(playerTown, otherTown)
    }

    fun getRelationshipOfPlayerToPlayer(player: Player, other: Player): DiplomaticRelationship {
        val playerTown = getTownFromPlayer(player)
        val otherTown = getTownFromPlayer(other)
        return getRelationshipOfTownToTown(playerTown, otherTown)
    }

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

    /**
     * Add event listener for protecting/unprotecting chests
     * with mouse clicks
     */
    internal fun startProtectingChests(resident: Resident) {
        val player = resident.player()
        if (player === null) {
            return
        }

        val town = resident.town
        if (town === null) {
            return
        }

        // check that resident is leader or officer
        if (resident !== town.leader && !town.officers.contains(resident)) {
            return
        }

        resident.isProtectingChests = true
    }

    /**
     * Remove event listener for protecting chests
     */
    internal fun stopProtectingChests(resident: Resident) {
        val player = resident.player()
        if (player === null) {
            return
        }

        // remove resident links
        resident.isProtectingChests = false
    }

    /**
     * Mark town chest as protected by town
     * Handles checking for connected chests.
     * Protect: true/false setting for protecting or unprotecting
     */
    internal fun protectTownChest(town: Town, block: BlockVec, protect: Boolean) {
        if (protect) {
            town.protectedBlocks.add(block)
        } else {
            town.protectedBlocks.remove(block)
        }

        town.needsUpdate()
        needsSave = true
    }

    /**
     * Generate particles at town's protected chests viewed by
     * input resident
     */
    internal fun showProtectedChests(town: Town, resident: Resident) {
        val player = resident.player()
        if (player === null) {
            return
        }

        val protectedBlocks = town.protectedBlocks

        // create repeating event to spawn particles each second
        val particle = Particle.HAPPY_VILLAGER
        val particleCount = 3
        val randomOffset = Vec(0.1, 0.1, 0.1)

        val maxRuns = 10
        var runCount = 0

        var task: Task? = null

        val runnable = Runnable {
            for (block in protectedBlocks) {
                // corners
                val location1 = Pos(block.x() + 0.1, block.y() + 0.5, block.z() + 0.1)
                val location2 = Pos(block.x() + 0.1, block.y() + 0.5, block.z() + 0.9)
                val location3 = Pos(block.x() + 0.9, block.y() + 0.5, block.z() + 0.1)
                val location4 = Pos(block.x() + 0.9, block.y() + 0.5, block.z() + 0.9)

                // centers
                val location5 = Pos(block.x() + 0.5, block.y() + 0.5, block.z())
                val location6 = Pos(block.x(), block.y() + 0.5, block.z() + 0.5)
                val location7 = Pos(block.x() + 0.5, block.y() + 0.5, block.z() + 1.0)
                val location8 = Pos(block.x() + 1.0, block.y() + 0.5, block.z() + 0.5)

                player.sendPackets(
                    ParticlePacket(particle, location1, randomOffset, 0F, particleCount),
                    ParticlePacket(particle, location2, randomOffset, 0F, particleCount),
                    ParticlePacket(particle, location3, randomOffset, 0F, particleCount),
                    ParticlePacket(particle, location4, randomOffset, 0F, particleCount),
                    ParticlePacket(particle, location5, randomOffset, 0F, particleCount),
                    ParticlePacket(particle, location6, randomOffset, 0F, particleCount),
                    ParticlePacket(particle, location7, randomOffset, 0F, particleCount),
                    ParticlePacket(particle, location8, randomOffset, 0F, particleCount),
                )
            }

            runCount += 1
            if (runCount > maxRuns) {
                task?.cancel()
            }
        }

        task = MinecraftServer.getSchedulerManager()
            .buildTask { runnable.run() }
            .delay(TaskSchedule.millis(1000))
            .repeat(TaskSchedule.millis(1000))
            .schedule()
    }

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

    fun destroyPort(port: Port) {
        // remove from ports map
        ports.remove(port.name)

        // remove chunk mappings
        val chunk = listOf(Math.floorDiv(port.locX, 16), Math.floorDiv(port.locZ, 16))
        chunkToPort.remove(chunk)

        needsSave = true
    }

    fun getPortFromName(name: String): Port? = ports.get(name)

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

    fun destroyPortGroup(portGroup: PortGroup) {
        // remove from portGroups map
        portGroups.remove(portGroup.name)

        needsSave = true
    }

    fun createPort(
        name: String,
        locX: Int,
        locZ: Int,
        groups: HashSet<PortGroup>,
        isPublic: Boolean,
    ): Result<Port> {
        // check if port already exists
        if (ports.containsKey(name)) {
            return Result.failure(ErrorPortExists)
        }

        val port = Port(name, locX, locZ, groups, isPublic)

        // save new port
        ports.put(name, port)

        // for each port, map the chunk its in to it
        val chunk = listOf(locX.floorDiv(16), locZ.floorDiv(16))
        chunkToPort.put(chunk, port)

        // mark dirty
        port.needsUpdate()
        needsSave = true

        return Result.success(port)
    }

    fun addPortToGroup(port: Port, group: PortGroup): Result<Port> {
        // check port is not already in this group
        if (port.groups.contains(group)) {
            return Result.failure(ErrorPortInGroup)
        }

        port.groups.add(group)
        port.needsUpdate()
        needsSave = true

        return Result.success(port)
    }

    fun removePortFromGroup(port: Port, group: PortGroup) {
        // check port is in group
        if (!port.groups.contains(group)) {
            return
        }

        port.groups.remove(group)
        port.needsUpdate()
        needsSave = true
    }

    /**
     * Get port owner based on who owns chunk
     * If no owner or if port is public, return null
     * If chunk is occupied, return occupier
     * Else, return territory town (may be null)
     */
    fun getPortOwner(port: Port): Town? {
        if (port.isPublic) {
            return null
        }

        val chunk = getTerritoryChunkFromCoord(Coord(port.chunkX, port.chunkZ))
        if (chunk === null) {
            return null
        }

        val occupier = chunk.occupier
        if (occupier !== null) {
            return occupier
        }

        return chunk.territory.town
    }

    /**
     * Check if two ports share a group
     */
    fun sharePortGroups(port1: Port, port2: Port): Boolean = port1.groups.any { it in port2.groups }
}
