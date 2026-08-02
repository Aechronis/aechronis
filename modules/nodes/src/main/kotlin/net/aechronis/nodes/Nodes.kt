/*
 * Nodes Engine/API
 */

package net.aechronis.nodes

import com.google.gson.JsonObject
import net.aechronis.nodes.commands.AllyChatCommand
import net.aechronis.nodes.commands.AllyCommand
import net.aechronis.nodes.commands.GlobalChatCommand
import net.aechronis.nodes.commands.NationChatCommand
import net.aechronis.nodes.commands.NationCommand
import net.aechronis.nodes.commands.NodesAdminCommand
import net.aechronis.nodes.commands.PlayerCommand
import net.aechronis.nodes.commands.PortCommand
import net.aechronis.nodes.commands.TerritoryCommand
import net.aechronis.nodes.commands.TownChatCommand
import net.aechronis.nodes.commands.TownCommand
import net.aechronis.nodes.commands.UnallyCommand
import net.aechronis.nodes.commands.WaypointCommand
import net.aechronis.nodes.listeners.NodesChatListener
import net.aechronis.nodes.listeners.NodesChestProtectionDestroyListener
import net.aechronis.nodes.listeners.NodesChestProtectionListener
import net.aechronis.nodes.listeners.NodesIncomeInventoryListener
import net.aechronis.nodes.listeners.NodesPlayerDamageListener
import net.aechronis.nodes.listeners.NodesPlayerJoinQuitListener
import net.aechronis.nodes.listeners.NodesPlayerMoveListener
import net.aechronis.nodes.listeners.NodesPlotSelectionListener
import net.aechronis.nodes.listeners.NodesWorldListener
import net.aechronis.nodes.objects.Building
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.MinimapPassengerTracker
import net.aechronis.nodes.objects.Nametag
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.OreBlockCache
import net.aechronis.nodes.objects.OreSampler
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.ResourceNode
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.objects.TerritoryPreprocessing
import net.aechronis.nodes.objects.TerritoryResources
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.objects.WaypointMenu
import net.aechronis.nodes.serdes.Deserializer
import net.aechronis.nodes.tasks.IncomeManager
import net.aechronis.nodes.tasks.SaveManager
import net.aechronis.nodes.tasks.TaskSaveBackup
import net.aechronis.nodes.tasks.TaskSaveBuildings
import net.aechronis.nodes.tasks.TaskSaveWorld
import net.aechronis.nodes.utils.loadLongFromFile
import net.aechronis.nodes.war.FlagWar
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.item.Material
import net.minestom.server.timer.Task
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.system.measureNanoTime

/** Global lifecycle, persistence, registries, and cross-domain engine coordination. */
object Nodes {
    val lowPriorityEventNode = EventNode.all("nodes-low-priority").setPriority(999)
    val eventNode = EventNode.all("nodes")
    val highPriorityEventNode = EventNode.all("nodes-high-priority").setPriority(-999)

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
    internal var needsSave: Boolean = false
    internal val hiddenOreInvalidBlocks: OreBlockCache = OreBlockCache(2000)
    lateinit var config: NodesConfig

    fun initialize(config: NodesConfig = NodesConfig()) {
        val timeStart = System.currentTimeMillis()
        this.config = config
        FlagWar.initialize(config.flagBlocks)
        println("Loading world from: $config.path")
        try {
            if (loadWorld()) {
                println("- Resource Nodes: ${ResourceNode.count()}")
                println("- Territories: ${Territory.count()}")
                println("- Residents: ${Resident.count()}")
                println("- Towns: ${Town.count()}")
                println("- Nations: ${Nation.count()}")
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
        MinimapPassengerTracker.init()
        NodesChatListener.init()
        NodesChestProtectionListener.init()
        NodesChestProtectionDestroyListener.init()
        NodesIncomeInventoryListener.init()
        NodesPlayerDamageListener.init()
        NodesPlayerJoinQuitListener.init()
        NodesPlayerMoveListener.init()
        NodesPlotSelectionListener.init()
        NodesWorldListener.init()
        WaypointMenu.init()
        MinecraftServer.getSchedulerManager().buildShutdownTask { cleanup() }
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
        MinecraftServer.getCommandManager().register(WaypointCommand())
        lastBackupTime = loadLongFromFile(config.pathLastBackupTime) ?: System.currentTimeMillis()
        reloadManagers()
        initializeOnlinePlayers()
        println("Enabled in ${System.currentTimeMillis() - timeStart}ms")
        println("now this is epic")
    }

    internal fun reloadManagers() {
        SaveManager.stop()
        IncomeManager.stop()
        Nametag.stop()
        SaveManager.start(config.savePeriod)
        IncomeManager.start()
        Nametag.start(config.nametagUpdatePeriod)
    }

    internal fun initializeOnlinePlayers() {
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            Resident.create(player)
            val resident = Resident.fromPlayer(player)!!
            Resident.setOnline(resident, player)
            if (resident.minimap == null) resident.createMinimap(player)
        }
    }

    internal fun cleanup() {
        residents.values.forEach { it.destroyMinimap() }
        towns.values.forEach { town -> if (town.income.pushToStorage(true)) town.needsUpdate() }
        if (FlagWar.enabled) FlagWar.cleanup()
        saveWorld(checkIfNeedsSave = false, async = false)
        Files.writeString(config.pathLastBackupTime, System.currentTimeMillis().toString())
    }

    internal fun loadResources(json: JsonObject) {
        resourceNodes.putAll(ResourceNode.loadFromJson(json))
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
                return
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
        residents.values.forEach { it.destroyMinimap() }

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
                return true
            }
            Deserializer.townsFromJson(config.pathTowns)
            residents.values.forEach { it.getSaveState() }
            towns.values.forEach { it.getSaveState() }
            nations.values.forEach { it.getSaveState() }
            FlagWar.load()
            if (!Files.exists(config.pathBuildings)) {
                System.err.println("No buildings found: ${config.pathBuildings}")
                return true
            }
            Deserializer.buildingsFromJson(config.pathBuildings)
            buildings.forEach { it.getSaveState() }
            return true
        } finally {
            for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                Resident.create(player)
                val resident = Resident.fromPlayer(player)!!
                Resident.setOnline(resident, player)
                resident.createMinimap(player)
            }
        }
    }

    internal fun saveWorld(checkIfNeedsSave: Boolean = true, async: Boolean = false) {
        if (!config.save) return
        val current = System.currentTimeMillis()
        val backup = current > lastBackupTime + config.backupPeriod
        val backupTimestamp = if (backup) current.also { lastBackupTime = it } else null
        if (needsSave || !checkIfNeedsSave) {
            saveWorldPreprocess()
            val timeUpdate = measureNanoTime {
                val task = TaskSaveWorld(residents.values.map { it.getSaveState() }, towns.values.map { it.getSaveState() }, nations.values.map { it.getSaveState() }, backupTimestamp)
                if (async) CompletableFuture.runAsync { task.run() } else task.run()
                needsSave = false
            }
            println("[Nodes] Saving world: ${timeUpdate}ns")
            val buildingTask = TaskSaveBuildings(buildings.map { it.getSaveState() }, config.pathBuildings)
            if (async) CompletableFuture.runAsync { buildingTask.run() } else buildingTask.run()
        } else if (backup) {
            val task = TaskSaveBackup(backupTimestamp!!)
            if (async) CompletableFuture.runAsync { task.run() } else task.run()
        }
    }

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
        val taxRate = config.taxIncomeRate.coerceIn(0.0, 1.0)
        val keptRate = 1.0 - taxRate
        towns.values.forEach { town ->
            try {
                val own = mutableMapOf<Material, Double>()
                val incomes = HashMap<Town, MutableMap<Material, Double>>()
                incomes[town] = own
                town.territories.forEach { id ->
                    val territory = Territory.fromId(id) ?: return@forEach
                    val territoryIncome = mutableMapOf<Material, Double>()
                    territory.income.forEach { (material, amount) -> territoryIncome[material] = (territoryIncome[material] ?: 0.0) + amount }
                    territory.chunks.forEach { coord ->
                        chunkToBuilding[listOf(coord.x, coord.z)]?.income()?.forEach { (material, amount) -> territoryIncome[material] = (territoryIncome[material] ?: 0.0) + amount }
                    }
                    territory.occupier?.let { occupier ->
                        val occupierIncome = incomes.getOrPut(occupier) { mutableMapOf() }
                        territoryIncome.forEach { (material, amount) ->
                            occupierIncome[material] = (occupierIncome[material] ?: 0.0) + amount * taxRate
                            own[material] = (own[material] ?: 0.0) + amount * keptRate
                        }
                    } ?: territoryIncome.forEach { (material, amount) -> own[material] = (own[material] ?: 0.0) + amount }
                }
                incomes.forEach { (_, income) -> income.forEach { (material, amount) -> rateToAmount(amount).takeIf { it > 0 }?.let { Town.addToIncome(town, material, it) } } }
            } catch (err: Exception) {
                println("Error running income for town ${town.name}")
                err.printStackTrace()
            }
        }
        Message.broadcast("Towns have collected income (use \"/t income\" to get)")
    }
}
