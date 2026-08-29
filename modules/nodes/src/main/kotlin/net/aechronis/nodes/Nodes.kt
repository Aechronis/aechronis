/*
 * Nodes Engine/API
 */

package net.aechronis.nodes

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.aechronis.nodes.colonization.Colonization
import net.aechronis.nodes.colonization.ColonizationMenu
import net.aechronis.nodes.commands.AllyChatCommand
import net.aechronis.nodes.commands.AllyCommand
import net.aechronis.nodes.commands.ColonizeCommand
import net.aechronis.nodes.commands.GlobalChatCommand
import net.aechronis.nodes.commands.NationChatCommand
import net.aechronis.nodes.commands.NationCommand
import net.aechronis.nodes.commands.NodesAdminCommand
import net.aechronis.nodes.commands.PlayerCommand
import net.aechronis.nodes.commands.PortCommand
import net.aechronis.nodes.commands.RatesCommand
import net.aechronis.nodes.commands.TerritoryCommand
import net.aechronis.nodes.commands.TownChatCommand
import net.aechronis.nodes.commands.TownCommand
import net.aechronis.nodes.commands.TrainCommand
import net.aechronis.nodes.commands.UnallyCommand
import net.aechronis.nodes.commands.WarzoneCommand
import net.aechronis.nodes.commands.WaypointCommand
import net.aechronis.nodes.listeners.NodesChatListener
import net.aechronis.nodes.listeners.NodesChestProtectionDestroyListener
import net.aechronis.nodes.listeners.NodesChestProtectionListener
import net.aechronis.nodes.listeners.NodesIncomeInventoryListener
import net.aechronis.nodes.listeners.NodesPlayerDamageListener
import net.aechronis.nodes.listeners.NodesPlayerJoinQuitListener
import net.aechronis.nodes.listeners.NodesPlayerMoveListener
import net.aechronis.nodes.listeners.NodesPlotSelectionListener
import net.aechronis.nodes.listeners.NodesVanillaStorageBridge
import net.aechronis.nodes.listeners.NodesWorldListener
import net.aechronis.nodes.listeners.TrainsListener
import net.aechronis.nodes.objects.Building
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.MinimapPassengerTracker
import net.aechronis.nodes.objects.MiningBoostManager
import net.aechronis.nodes.objects.Nametag
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.OreBlockCache
import net.aechronis.nodes.objects.OreSampler
import net.aechronis.nodes.objects.RelationshipHitbox
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.ResourceNode
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.objects.TerritoryPreprocessing
import net.aechronis.nodes.objects.TerritoryResources
import net.aechronis.nodes.objects.TestTownSelection
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.objects.Trains
import net.aechronis.nodes.objects.WaypointMenu
import net.aechronis.nodes.serdes.Deserializer
import net.aechronis.nodes.tasks.IncomeCalculator
import net.aechronis.nodes.tasks.IncomeManager
import net.aechronis.nodes.tasks.SaveManager
import net.aechronis.nodes.tasks.SerialSaveQueue
import net.aechronis.nodes.tasks.TaskSaveBackup
import net.aechronis.nodes.tasks.TaskSaveBuildings
import net.aechronis.nodes.tasks.TaskSaveWorld
import net.aechronis.nodes.utils.loadLongFromFile
import net.aechronis.nodes.war.Alliance
import net.aechronis.nodes.war.FlagWar
import net.aechronis.nodes.war.Warzone
import net.aechronis.nodes.war.serdes.WarSerializer
import net.aechronis.server.modules.ModuleEvents
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.timer.Task
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureNanoTime
import net.minestom.server.command.builder.Command as MinestomCommand

/** Global lifecycle, persistence, registries, and cross-domain engine coordination. */
object Nodes {
    val lowPriorityEventNode = EventNode.all("nodes-low-priority").setPriority(999)
    val eventNode = EventNode.all("nodes")
    val highPriorityEventNode = EventNode.all("nodes-high-priority").setPriority(-999)
    val postPermissionEventNode = EventNode.all("nodes-post-permission").setPriority(-998)

    internal val resourceNodes: HashMap<String, ResourceNode> = hashMapOf()
    internal val territoryChunks: ConcurrentHashMap<Coord, TerritoryChunk> = ConcurrentHashMap()
    internal val territories: HashMap<TerritoryId, Territory> = hashMapOf()
    internal val towns: LinkedHashMap<String, Town> = LinkedHashMap()
    internal val nations: LinkedHashMap<String, Nation> = LinkedHashMap()
    internal val residents: LinkedHashMap<UUID, Resident> = LinkedHashMap()
    internal val buildings: MutableList<Building> = mutableListOf()
    internal val minimapBuildingsByChunk: ConcurrentHashMap<Coord, Building> = ConcurrentHashMap()
    var playerWarpTasks: HashMap<Player, Task> = hashMapOf()
    var chunkToBuilding: HashMap<List<Int>, Building> = hashMapOf()
    internal var lastBackupTime: Long = 0
    val war = FlagWar
    private val initialized = AtomicBoolean()
    private var initializationComplete = false
    private val completedCleanupStages = mutableSetOf<CleanupStage>()
    private var commands: List<MinestomCommand> = emptyList()

    internal val occupationPersistenceLock = Any()
    private val saveQueue = SerialSaveQueue()
    private var saveRevision = 0L
    private var lastQueuedRevision = -1L
    private var backupPending = false
    internal var needsSave: Boolean = false
        set(value) {
            synchronized(occupationPersistenceLock) {
                field = value
                if (value) saveRevision++
            }
        }
    internal val hiddenOreInvalidBlocks: OreBlockCache = OreBlockCache(2000)
    lateinit var config: NodesConfig

    fun initialize(config: NodesConfig = NodesConfig()) {
        check(initialized.compareAndSet(false, true)) { "Nodes is already initialized" }
        initializationComplete = false
        completedCleanupStages.clear()
        val timeStart = System.currentTimeMillis()
        this.config = config
        WarSerializer.resume()
        FlagWar.initialize(config.flagBlocks)
        println("Loading world from: $config.path")
        check(loadWorld()) {
            "Invalid world file at ${config.path}/${config.pathWorld}; refusing to start Nodes with partial state"
        }
        println("- Resource Nodes: ${ResourceNode.count()}")
        println("- Territories: ${Territory.count()}")
        println("- Residents: ${Resident.count()}")
        println("- Towns: ${Town.count()}")
        println("- Nations: ${Nation.count()}")
        MinimapPassengerTracker.init()
        RelationshipHitbox.init()
        NodesChatListener.init()
        NodesChestProtectionListener.init()
        NodesIncomeInventoryListener.init()
        NodesPlayerDamageListener.init()
        NodesPlayerJoinQuitListener.init()
        NodesPlayerMoveListener.init()
        NodesPlotSelectionListener.init()
        NodesWorldListener.init()
        NodesChestProtectionDestroyListener.init()
        NodesVanillaStorageBridge.init()
        ColonizationMenu.init()
        TrainsListener.init()
        WaypointMenu.init()
        TestTownSelection.init()
        Trains.initialize(config.pathTrains)
        commands =
            listOf(
                TownCommand(),
                NationCommand(),
                NodesAdminCommand(),
                AllyCommand(),
                UnallyCommand(),
                GlobalChatCommand(),
                TownChatCommand(),
                NationChatCommand(),
                AllyChatCommand(),
                PlayerCommand(),
                TerritoryCommand(),
                RatesCommand(),
                PortCommand(),
                WaypointCommand(),
                TrainCommand(),
                ColonizeCommand(),
                WarzoneCommand(),
            )
        val globalEventHandler = MinecraftServer.getGlobalEventHandler()
        ModuleEvents.addChild(globalEventHandler, lowPriorityEventNode)
        ModuleEvents.addChild(globalEventHandler, eventNode)
        ModuleEvents.addChild(globalEventHandler, highPriorityEventNode)
        ModuleEvents.addChild(globalEventHandler, postPermissionEventNode)
        commands.forEach(MinecraftServer.getCommandManager()::register)
        lastBackupTime = loadLongFromFile(config.pathLastBackupTime) ?: System.currentTimeMillis()
        reloadManagers()
        MiningBoostManager.start()
        initializeOnlinePlayers()
        initializationComplete = true
        println("Enabled in ${System.currentTimeMillis() - timeStart}ms")
        println("now this is epic")
    }

    internal fun reloadManagers() {
        SaveManager.stop()
        IncomeManager.stop()
        Nametag.stop()
        SaveManager.start(config.savePeriod)
        IncomeManager.start()
        Nametag.start()
    }

    internal fun prepareForShutdown() {
        SaveManager.stop()
        try {
            Colonization.prepareForShutdown(SHUTDOWN_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for Nodes background work to finish", error)
        } catch (error: TimeoutException) {
            throw IllegalStateException(
                "Nodes background work did not finish within $SHUTDOWN_DRAIN_TIMEOUT_SECONDS seconds",
                error,
            )
        }
        awaitSaveQueueQuiescence()
        awaitWarWrites()
    }

    internal fun initializeOnlinePlayers() {
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            Resident.create(player)
            val resident = Resident.fromPlayer(player)!!
            Resident.setOnline(resident, player)
            MiningBoostManager.onPlayerJoin(player)
            Warzone.onPlayerTerritoryChanged(player, Territory.fromPlayer(player))
            if (resident.minimap == null) resident.createMinimap(player)
        }
    }

    @Synchronized
    internal fun cleanup() {
        if (!initialized.get()) return
        val persistState = initializationComplete

        val globalEventHandler = MinecraftServer.getGlobalEventHandler()
        cleanupStage(CleanupStage.EVENTS) {
            listOf(lowPriorityEventNode, eventNode, highPriorityEventNode, postPermissionEventNode).forEach { node ->
                globalEventHandler.removeChild(node)
            }
        }
        val commandManager = MinecraftServer.getCommandManager()
        cleanupStage(CleanupStage.COMMANDS) {
            commands.forEach { command -> commandManager.unregister(command) }
            commands = emptyList()
        }

        cleanupStage(CleanupStage.SAVE_MANAGER, SaveManager::stop)
        cleanupStage(CleanupStage.INCOME_MANAGER, IncomeManager::stop)
        cleanupStage(CleanupStage.NAMETAG, Nametag::stop)
        cleanupStage(CleanupStage.MINING_BOOST, MiningBoostManager::stop)
        cleanupStage(CleanupStage.COLONIZATION_MENUS, ColonizationMenu::closeAll)
        cleanupStage(CleanupStage.WAYPOINT_MENUS, WaypointMenu::closeAll)
        cleanupStage(CleanupStage.WARP_TASKS) {
            playerWarpTasks.values.forEach(Task::cancel)
            playerWarpTasks.clear()
        }
        cleanupStage(CleanupStage.RESIDENTS) {
            residents.values.forEach { resident ->
                resident.destroyMinimap()
                resident.clearPlotSelection()
                resident.teleportThread?.cancel()
                resident.teleportThread = null
                resident.inviteThread?.cancel()
                resident.inviteThread = null
            }
        }
        cleanupStage(CleanupStage.TOWNS) {
            towns.values.forEach { town ->
                town.applications.values.forEach(Task::cancel)
                town.applications.clear()
                if (persistState && town.income.pushToStorage(true)) town.needsUpdate()
            }
        }
        cleanupStage(CleanupStage.ALLIANCE, Alliance::cleanup)
        cleanupStage(CleanupStage.TRAINS) { Trains.cleanup(persistState) }
        cleanupStage(CleanupStage.FLAG_WAR) { FlagWar.cleanup(persistState) }
        cleanupStage(CleanupStage.WARZONE) { Warzone.cleanup(persistState) }
        cleanupStage(CleanupStage.COLONIZATION, Colonization::cleanup)
        // A generation whose initialize call failed may contain cleared or partially loaded maps.
        // Tear it down, but never let that candidate overwrite the last valid snapshot used by rollback.
        cleanupStage(CleanupStage.FINAL_SAVE) {
            if (persistState) saveWorld(checkIfNeedsSave = false, async = false)
        }
        initializationComplete = false
        initialized.set(false)
    }

    private inline fun cleanupStage(
        stage: CleanupStage,
        action: () -> Unit,
    ) {
        if (stage in completedCleanupStages) return
        action()
        completedCleanupStages += stage
    }

    private enum class CleanupStage {
        EVENTS,
        COMMANDS,
        SAVE_MANAGER,
        INCOME_MANAGER,
        NAMETAG,
        MINING_BOOST,
        COLONIZATION_MENUS,
        WAYPOINT_MENUS,
        WARP_TASKS,
        RESIDENTS,
        TOWNS,
        ALLIANCE,
        TRAINS,
        FLAG_WAR,
        WARZONE,
        COLONIZATION,
        FINAL_SAVE,
    }

    internal fun loadResources(json: JsonObject) {
        resourceNodes.putAll(ResourceNode.loadFromJson(json))
    }

    /**
     * Add or remove a resource node in the world definition and hot-reload the affected territories.
     */
    internal fun updateTerritoryResourceNode(
        territoryId: TerritoryId,
        resourceNodeName: String,
        add: Boolean,
    ): Result<Unit> = runCatching {
        if (!resourceNodes.containsKey(resourceNodeName)) {
            error("Resource node '$resourceNodeName' does not exist")
        }

        val currentTerritory = territories[territoryId] ?: error("Territory '$territoryId' does not exist")
        val path = config.pathWorld
        val root = Files.newBufferedReader(path).use { reader ->
            JsonParser.parseReader(reader).asJsonObject
        }
        val territoriesJson = root.get("territories")?.asJsonObject
            ?: error("World file does not contain territories")
        val territoryJson = territoriesJson.get(territoryId.toString())?.asJsonObject
            ?: error("World file does not contain territory '$territoryId'")

        val nodes = territoryJson.get("nodes")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.map { it.asString }
            ?.toMutableList()
            ?: mutableListOf()

        if (add) {
            if (nodes.contains(resourceNodeName)) {
                error("Resource node '$resourceNodeName' is already assigned to territory '$territoryId'")
            }
            nodes.add(resourceNodeName)
        } else if (!nodes.removeAll { it == resourceNodeName }) {
            error("Resource node '$resourceNodeName' is not assigned to territory '$territoryId'")
        }

        val updatedNodes = JsonArray()
        nodes.forEach(updatedNodes::add)
        territoryJson.add("nodes", updatedNodes)

        val parent = path.parent ?: Paths.get(".")
        val temporaryPath = Files.createTempFile(parent, "world-", ".json.tmp")
        try {
            Files.writeString(temporaryPath, GsonBuilder().create().toJson(root))
            try {
                Files.move(
                    temporaryPath,
                    path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporaryPath)
        }

        val reloadIds = buildList {
            add(territoryId)
            for (neighborId in currentTerritory.neighbors) add(neighborId)
        }.distinct()
        loadTerritories(territoriesJson, reloadIds)
    }

    internal fun loadTerritories(json: JsonObject, ids: List<TerritoryId>? = null) {
        val preprocessing = TerritoryPreprocessing.loadFromJson(json, ids)
        val graph = HashMap<TerritoryId, TerritoryResources>()
        if (ids != null) {
            val neighbors = hashSetOf<TerritoryId>()
            ids.forEach { id ->
                territories[id]?.let { territory ->
                    for (neighborId in territory.neighbors) {
                        territories[neighborId]?.let { neighbor ->
                            neighbors.add(neighborId)
                            for (neighborNeighborId in neighbor.neighbors) neighbors.add(neighborNeighborId)
                        }
                    }
                }
            }
            neighbors.forEach { id ->
                territories[id]?.let { territory ->
                    val resources = territory.resourceNodes.map { resourceNodes[it] ?: error("Resource node '$it' does not exist (for territory id=${territory.id})") }.sortedBy { it.priority }
                    graph[id] = resources.fold(config.globalResources.copy()) { current, resource -> resource.apply(current) }
                }
            }
        }
        preprocessing.forEach { territory ->
            val resources = territory.resourceNodes.map { resourceNodes[it] ?: error("Resource node '$it' does not exist (for territory id=${territory.id})") }.sortedBy { it.priority }
            graph[territory.id] = resources.fold(config.globalResources.copy()) { current, resource -> resource.apply(current) }
        }
        val toBuild = if (ids == null) {
            preprocessing
        } else {
            val neighborIds = hashSetOf<TerritoryId>()
            preprocessing.filter { graph[it.id]!!.hasNeighborModifier }.forEach { territory ->
                for (neighborId in territory.neighbors) neighborIds.add(neighborId)
            }
            preprocessing.forEach { neighborIds.remove(it.id) }
            preprocessing + neighborIds.mapNotNull { territories[it]?.toPreprocessing() }
        }
        toBuild.forEach { territory ->
            var resources = graph[territory.id] ?: return@forEach
            for (neighborId in territory.neighbors) {
                graph[neighborId]?.takeIf { it.hasNeighborModifier }?.let { resources = resources.accumulateNeighborModifiers(it) }
            }
            graph[territory.id] = resources
        }
        toBuild.forEach { data ->
            if (!data.chunks.contains(data.core)) {
                println("[Nodes] Territory ${data.id} chunk does not contain core")
                return@forEach
            }
            val resources = graph[data.id]!!.applyNeighborModifiers()
            val names = data.resourceNodes.sortedBy { resourceNodes[it]!!.priority }
            val territory = Territory(data.id, data.name, data.color, data.core, data.chunks, data.bordersWilderness, data.neighbors, names, resources.income, OreSampler(ArrayList(resources.ores.values)), resources.attackerTimeMultiplier, resources.defenderTimeMultiplier)
            territories[data.id]?.let { old ->
                old.chunks.forEach(territoryChunks::remove)
                territory.town = old.town
                territory.occupier = old.occupier
            }
            territories[data.id] = territory
            data.chunks.forEach { territoryChunks[it] = TerritoryChunk(it, territory) }
        }
    }

    internal fun loadWorld(): Boolean {
        FlagWar.resetForReload()
        Warzone.resetForReload()
        Colonization.resetForReload()
        residents.values.forEach { it.destroyMinimap() }
        MiningBoostManager.reset()

        var loaded = false
        try {
            resourceNodes.clear()
            territoryChunks.clear()
            territories.clear()
            towns.clear()
            nations.clear()
            residents.clear()
            buildings.clear()
            minimapBuildingsByChunk.clear()
            chunkToBuilding.clear()
            if (!Files.exists(config.pathWorld)) {
                System.err.println("Failed to load world: ${config.pathWorld}")
                return false
            }
            val (resources, territoriesJson) = Deserializer.worldFromJson(config.pathWorld)
            if (resources != null) loadResources(resources)
            if (territoriesJson != null) loadTerritories(territoriesJson)
            if (!Files.exists(config.pathTowns)) {
                System.err.println("No towns found: ${config.pathTowns}")
                loaded = true
                return true
            }
            Deserializer.townsFromJson(config.pathTowns)
            residents.values.forEach { it.getSaveState() }
            towns.values.forEach { it.getSaveState() }
            nations.values.forEach { it.getSaveState() }
            FlagWar.load()
            Warzone.load()
            if (!Files.exists(config.pathBuildings)) {
                System.err.println("No buildings found: ${config.pathBuildings}")
                loaded = true
                return true
            }
            Deserializer.buildingsFromJson(config.pathBuildings)
            buildings.forEach { it.getSaveState() }
            loaded = true
            return true
        } finally {
            if (loaded) {
                for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                    Resident.create(player)
                    val resident = Resident.fromPlayer(player)!!
                    Resident.setOnline(resident, player)
                    MiningBoostManager.onPlayerJoin(player)
                    Warzone.onPlayerTerritoryChanged(player, Territory.fromPlayer(player))
                    resident.createMinimap(player)
                }
                Nametag.rebuildAllViewers()
            }
        }
    }

    fun saveWorld(
        checkIfNeedsSave: Boolean = true,
        async: Boolean = false,
    ): CompletableFuture<Void> {
        if (!config.save) return CompletableFuture.completedFuture(null)
        val current = System.currentTimeMillis()
        val request =
            try {
                synchronized(occupationPersistenceLock) {
                    val dirtyRevision = saveRevision
                    val saveState =
                        !checkIfNeedsSave ||
                            (needsSave && dirtyRevision > lastQueuedRevision)
                    val backup = !backupPending && current > lastBackupTime + config.backupPeriod
                    if (!saveState && !backup) {
                        val pending = saveQueue.current()
                        if (!async) awaitSaveQueue("waiting for the current Nodes save")
                        return pending
                    }

                    val backupTimestamp = current.takeIf { backup }

                    if (saveState) {
                        // failed occupation journal write must not be followed by
                        // a towns.json snapshot of the newer in-memory relationship
                        FlagWar.flushTerritoryOccupationJournal()
                        saveWorldPreprocess()
                        lastQueuedRevision = dirtyRevision
                        SaveRequest(
                            worldTask = TaskSaveWorld(
                                residents.values.map { it.getSaveState() },
                                towns.values.map { it.getSaveState() },
                                nations.values.map { it.getSaveState() },
                                backupTimestamp,
                            ),
                            buildingTask = TaskSaveBuildings(buildings.map { it.getSaveState() }, config.pathBuildings),
                            revision = dirtyRevision,
                            backupTimestamp = backupTimestamp,
                        ).also { if (backupTimestamp != null) backupPending = true }
                    } else {
                        SaveRequest(
                            backupTask = TaskSaveBackup(backupTimestamp!!),
                            backupTimestamp = backupTimestamp,
                        ).also { backupPending = true }
                    }
                }
            } catch (error: Throwable) {
                synchronized(occupationPersistenceLock) {
                    needsSave = true
                    backupPending = false
                }
                System.err.println("[Nodes] Failed to prepare world save: ${error.message}")
                error.printStackTrace()
                val failure = CompletableFuture.failedFuture<Void>(error)
                if (!async) throw error
                return failure
            }

        val future = enqueueSave(request)
        if (!async) awaitSaveQueue("completing a synchronous Nodes save")
        return future
    }

    private fun awaitSaveQueue(operation: String) {
        try {
            saveQueue.awaitIdle(SHUTDOWN_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while $operation", error)
        } catch (error: TimeoutException) {
            throw IllegalStateException(
                "$operation did not finish within $SHUTDOWN_DRAIN_TIMEOUT_SECONDS seconds",
                error,
            )
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun awaitSaveQueueQuiescence() {
        try {
            saveQueue.awaitQuiescence(SHUTDOWN_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for Nodes saves to quiesce", error)
        } catch (error: TimeoutException) {
            throw IllegalStateException(
                "Nodes saves did not quiesce within $SHUTDOWN_DRAIN_TIMEOUT_SECONDS seconds",
                error,
            )
        }
    }

    private fun awaitWarWrites() {
        try {
            WarSerializer.prepareForShutdown(SHUTDOWN_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for the war-state writer", error)
        } catch (error: TimeoutException) {
            throw IllegalStateException(
                "The war-state writer did not finish within $SHUTDOWN_DRAIN_TIMEOUT_SECONDS seconds",
                error,
            )
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun enqueueSave(request: SaveRequest): CompletableFuture<Void> {
        val future =
            saveQueue.submit {
                val elapsed =
                    measureNanoTime {
                        request.worldTask?.run()
                        request.buildingTask?.run()
                        request.backupTask?.run()
                    }
                println("[Nodes] Saved world in ${elapsed}ns")
            }

        future.whenComplete { _, error ->
            synchronized(occupationPersistenceLock) {
                if (error == null) {
                    request.revision?.let { revision ->
                        if (saveRevision == revision) needsSave = false
                    }
                    request.backupTimestamp?.let { timestamp -> lastBackupTime = timestamp }
                } else if (request.revision != null) {
                    needsSave = true
                }
                if (request.backupTimestamp != null) backupPending = false
            }
            if (error != null) {
                System.err.println("[Nodes] Failed to save world: ${error.message}")
                error.printStackTrace()
            }
        }
        return future
    }

    private data class SaveRequest(
        val worldTask: TaskSaveWorld? = null,
        val buildingTask: TaskSaveBuildings? = null,
        val backupTask: TaskSaveBackup? = null,
        val revision: Long? = null,
        val backupTimestamp: Long? = null,
    )

    private const val SHUTDOWN_DRAIN_TIMEOUT_SECONDS = 60L

    internal fun saveWorldPreprocess() {
        towns.values.forEach { town -> if (town.income.pushToStorage(false)) town.needsUpdate() }
    }

    /** Cross-domain income engine. */
    fun runIncome() {
        fun rateToAmount(rate: Double): Int {
            if (rate <= 0.0) return 0
            val integer = kotlin.math.floor(rate)
            val fractional = kotlin.math.max(0.0, rate - integer)
            return integer.toInt() + if (fractional > 0.0 && ThreadLocalRandom.current().nextDouble() < fractional) 1 else 0
        }

        val incomes = IncomeCalculator.calculate()
        incomes.forEach { (town, income) ->
            try {
                income.forEach { (material, amount) ->
                    rateToAmount(amount).takeIf { it > 0 }?.let { Town.addToIncome(town, material, it) }
                }
            } catch (err: Exception) {
                println("Error running income for town ${town.name}")
                err.printStackTrace()
            }
        }
        Message.broadcast("Towns have collected income (use \"/t income\" to get)")
    }
}
