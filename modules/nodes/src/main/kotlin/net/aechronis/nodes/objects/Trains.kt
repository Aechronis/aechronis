package net.aechronis.nodes.objects

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.aechronis.nodes.Message
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.item.Material
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

data class TrainStation(
    val id: Int,
    val position: BlockVec,
    var tier: Int = 0,
    var banned: Boolean = false,
)

data class RailEdge(
    val stationId: Int,
    val start: BlockVec,
    val end: BlockVec,
    val destinationId: Int?,
    val distance: Int,
)

object Trains {
    private data class Journey(val start: BlockVec, val task: Task)
    private data class StationAttachment(val stationId: Int, val start: BlockVec)
    private data class RailComponent(
        val id: Int,
        val rails: Set<BlockVec>,
        val edges: List<RailEdge>,
    )

    private val stations = linkedMapOf<Int, TrainStation>()
    private val stationsByPosition = hashMapOf<BlockVec, Int>()
    private val edges = mutableListOf<RailEdge>()
    private val edgesByStart = hashMapOf<BlockVec, RailEdge>()
    private val edgesByStation = hashMapOf<Int, MutableList<RailEdge>>()

    /** All possible station-to-rail positions; changes only when stations do. */
    private val attachmentCandidatesByRail = hashMapOf<BlockVec, MutableList<StationAttachment>>()

    /** Candidates that currently contain a rail whose shape connects to the station. */
    private val attachmentsByRail = hashMapOf<BlockVec, MutableList<StationAttachment>>()
    private val components = hashMapOf<Int, RailComponent>()
    private val componentByRail = hashMapOf<BlockVec, Int>()
    private val journeys = hashMapOf<UUID, Journey>()
    private val directions = listOf(
        BlockVec(0, 0, -1),
        BlockVec(0, 0, 1),
        BlockVec(1, 0, 0),
        BlockVec(-1, 0, 0),
    )
    private val verticalOffsets = listOf(-1, 0, 1)
    private val railKeys = setOf(
        Block.RAIL.key(),
        Block.POWERED_RAIL.key(),
        Block.DETECTOR_RAIL.key(),
        Block.ACTIVATOR_RAIL.key(),
    )
    private var nextStationId = 1
    private var nextComponentId = 1
    private var scanTask: Task? = null
    private var pendingRescan: Task? = null
    private var pendingSave: Task? = null
    private var pendingInstance: Instance? = null
    private val dirtyPositions = hashSetOf<BlockVec>()
    private lateinit var path: Path

    fun initialize(path: Path) {
        this.path = path
        load()
        scanAll(activeInstance())
        // Block updates use requestRescan and are applied next tick. This is only
        // a reconciliation pass for changes which did not emit a block event.
        scanTask = MinecraftServer.getSchedulerManager().buildTask { scanAll(activeInstance()) }
            .delay(TaskSchedule.tick(40)).repeat(TaskSchedule.tick(40)).schedule()
    }

    fun cleanup() {
        scanTask?.cancel()
        pendingRescan?.cancel()
        pendingSave?.cancel()
        scanTask = null
        pendingRescan = null
        pendingSave = null
        pendingInstance = null
        dirtyPositions.clear()
        journeys.values.forEach { it.task.cancel() }
        journeys.clear()
        save()
    }

    /** Coalesce same-tick world edits and rebuild only their old/new rail components. */
    fun requestRescan(instance: Instance?, position: BlockVec? = null) {
        if (instance == null) return
        pendingInstance = instance
        position?.let(dirtyPositions::add)
        if (pendingRescan != null) return
        pendingRescan = MinecraftServer.getSchedulerManager().buildTask {
            pendingRescan = null
            val target = pendingInstance
            pendingInstance = null
            if (target != null) {
                if (dirtyPositions.isEmpty()) scanAll(target) else rebuildDirtyComponents(target)
            }
        }.delay(TaskSchedule.tick(1)).schedule()
    }

    fun allStations(): List<TrainStation> = stations.values.toList()
    fun station(id: Int): TrainStation? = stations[id]

    /** True for a rail/station mutation or removal adjacent to indexed track. */
    fun affectsTopology(position: BlockVec, block: Block): Boolean = isRail(block) ||
        block == Block.GOLD_BLOCK ||
        nearbyRailPositions(position).any { it in componentByRail } ||
        stationsByPosition.containsKey(position)

    fun create(position: BlockVec, instance: Instance): Result<TrainStation> = runCatching {
        require(instance.getBlock(position) == Block.GOLD_BLOCK) { "Stations must be created on a gold block" }
        require(position !in stationsByPosition) { "A station already exists at this gold block" }
        TrainStation(nextStationId++, position).also {
            stations[it.id] = it
            stationsByPosition[position] = it.id
            rebuildAttachmentIndex(instance)
            // The new station may terminate a previously open route, so build the
            // reachable components once rather than scanning every station route.
            scanAll(instance)
            save()
        }
    }

    fun remove(id: Int): TrainStation? {
        val station = stations.remove(id) ?: return null
        stationsByPosition.remove(station.position)
        rebuildAttachmentIndex(activeInstance())
        // Include every former attachment; component membership before removal
        // lets the next-tick rebuild repair both sides of a split route.
        stationStarts(station.position).forEach { requestRescan(activeInstance(), it) }
        save()
        return station
    }

    fun setTier(id: Int, tier: Int): Result<TrainStation> = runCatching {
        require(tier in 0..3) { "Tier must be between 0 and 3" }
        val station = stations[id] ?: error("Station not found")
        station.tier = tier
        save()
        station
    }

    fun setBanned(id: Int, banned: Boolean): Result<TrainStation> = runCatching {
        val station = stations[id] ?: error("Station not found")
        station.banned = banned
        save()
        station
    }

    fun scan(id: Int, instance: Instance): Result<Int> = runCatching {
        val station = stations[id] ?: error("Station not found")
        if (instance.getBlock(station.position) != Block.GOLD_BLOCK) {
            remove(id)
            return@runCatching 0
        }
        rebuildAttachmentIndex(instance)
        val starts = stationStarts(station.position)
        val affected = starts.mapNotNull(componentByRail::get).toSet()
        affected.forEach(::removeComponent)
        buildComponents(instance, starts)
        save()
        edgesByStation[station.id]?.size ?: 0
    }

    /**
     * Reconcile every station-reachable rail component. Each rail is visited at
     * most once, attachment lookup is indexed, and edge indexes are built once:
     * O(S + R + E), not O(S * R) or O(S * E).
     */
    fun scanAll(instance: Instance?): Int {
        if (instance == null) return 0
        var changed = false
        val oldEdges = edges.toSet()
        stations.values.toList().forEach { station ->
            if (instance.getBlock(station.position) != Block.GOLD_BLOCK) {
                stations.remove(station.id)
                stationsByPosition.remove(station.position)
                changed = true
            }
        }
        rebuildAttachmentIndex(instance)
        clearTopology()
        buildComponents(instance, attachmentsByRail.keys)
        if (changed || oldEdges != edges.toSet()) scheduleSave()
        return edges.size
    }

    fun edgesFrom(id: Int): List<RailEdge> = edgesByStation[id]?.toList() ?: emptyList()

    fun startJourney(player: Player, instance: Instance, rail: BlockVec): Result<Unit> = runCatching {
        val edge = edgesByStart[rail] ?: error("No train leaves from this rail")
        val source = stations[edge.stationId] ?: error("Station not found")
        require(!source.banned) { "This station is banned" }
        val destination = edge.destinationId?.let(stations::get)
        require(destination?.banned != true) { "Destination station is banned" }
        cancel(player)
        val speed = speed(source.tier)
        val duration = max(1L, ceil(edge.distance * 1000.0 / speed).toLong())
        val start = player.position.asBlockVec()
        val startedAt = System.currentTimeMillis()
        lateinit var task: Task
        task = MinecraftServer.getSchedulerManager().buildTask {
            val elapsed = System.currentTimeMillis() - startedAt
            if (!player.isOnline || elapsed >= duration) {
                journeys.remove(player.uuid)
                task.cancel()
                if (player.isOnline) {
                    val target = destination?.position ?: edge.end
                    player.teleport(Pos(target.x() + .5, target.y() + 1.0, target.z() + .5, player.position.yaw, player.position.pitch))
                    Message.print(player, "Arrived at destination")
                }
                return@buildTask
            }
            Message.announcement(player, "${progressBar(elapsed.toDouble() / duration)} ${speed.toInt()} blocks/s")
        }.repeat(TaskSchedule.tick(2)).schedule()
        journeys[player.uuid] = Journey(start, task)
    }

    fun cancelIfMoved(player: Player, position: BlockVec) {
        if (journeys[player.uuid]?.start != null && journeys[player.uuid]?.start != position) {
            cancel(player)
            Message.error(player, "Train journey cancelled due to movement")
        }
    }

    fun cancel(player: Player, message: String? = null) {
        journeys.remove(player.uuid)?.task?.cancel()
        message?.let { Message.error(player, it) }
    }

    fun removeAt(position: BlockVec): TrainStation? = stationsByPosition[position]?.let(::remove)

    fun incomeAt(chunkX: Int, chunkZ: Int): Map<Material, Double> = stations.values.asSequence()
        .filter { it.tier > 0 && Math.floorDiv(it.position.blockX(), 16) == chunkX && Math.floorDiv(it.position.blockZ(), 16) == chunkZ }
        .fold(mutableMapOf()) { income, station ->
            income[Material.COAL] = (income[Material.COAL] ?: 0.0) + coalIncome(station.tier)
            income
        }

    fun speed(tier: Int): Double = when (tier) {
        0, 1 -> 25.0
        2 -> 50.0
        else -> 100.0
    }
    private fun coalIncome(tier: Int): Double = when (tier) {
        1 -> 8.0
        2 -> 16.0
        3 -> 32.0
        else -> 0.0
    }

    private fun rebuildDirtyComponents(instance: Instance) {
        val dirty = dirtyPositions.toSet()
        dirtyPositions.clear()
        val oldComponentIds = hashSetOf<Int>()
        val seeds = hashSetOf<BlockVec>()
        val dirtyArea = dirty.flatMapTo(hashSetOf(), ::nearbyRailPositions)
        refreshAttachments(instance, dirtyArea)
        dirtyArea.forEach { position ->
            seeds += position
            componentByRail[position]?.let(oldComponentIds::add)
        }
        // A removed bridge no longer appears in the world, so seed every rail in
        // its old component. BFS then discovers each resulting split exactly once.
        oldComponentIds.forEach { id -> components[id]?.rails?.let(seeds::addAll) }
        oldComponentIds.forEach(::removeComponent)
        buildComponents(instance, seeds)
        scheduleSave()
    }

    private fun buildComponents(instance: Instance, seeds: Iterable<BlockVec>) {
        val visited = hashSetOf<BlockVec>()
        seeds.forEach { seed ->
            if (seed in visited || componentByRail.containsKey(seed) || !isRail(instance.getBlock(seed))) return@forEach
            val rails = collectComponent(instance, seed, visited)
            if (rails.isEmpty()) return@forEach
            val component = RailComponent(nextComponentId++, rails, buildEdges(instance, rails))
            components[component.id] = component
            rails.forEach { componentByRail[it] = component.id }
            addEdges(component.edges)
        }
    }

    /** Breadth-first search visits each physical rail once in the component. */
    private fun collectComponent(instance: Instance, seed: BlockVec, globalVisited: MutableSet<BlockVec>): Set<BlockVec> {
        val rails = linkedSetOf<BlockVec>()
        val queue = ArrayDeque<BlockVec>()
        queue += seed
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!globalVisited.add(current) || !isRail(instance.getBlock(current))) continue
            rails += current
            railExits(current, instance.getBlock(current)).forEach { next ->
                if (isRail(instance.getBlock(next)) && current in railExits(next, instance.getBlock(next))) queue += next
            }
        }
        return rails
    }

    /** Each valid station attachment walks only to its next station/dead end. */
    private fun buildEdges(instance: Instance, rails: Set<BlockVec>): List<RailEdge> = rails.asSequence()
        .flatMap { rail -> attachmentsByRail[rail].orEmpty().asSequence() }
        .mapNotNull { attachment -> traceEdge(instance, rails, attachment) }
        .toList()

    private fun traceEdge(instance: Instance, rails: Set<BlockVec>, attachment: StationAttachment): RailEdge? {
        val source = stations[attachment.stationId] ?: return null
        var previous = source.position
        var current = attachment.start
        var distance = 0
        val visited = hashSetOf<BlockVec>()
        while (current in rails && visited.add(current) && distance < 10_000) {
            distance++
            val exits = railExits(current, instance.getBlock(current)).filter { it != previous }
            val destination = exits.firstOrNull { stationsByPosition[it] != null }
            if (destination != null) {
                return RailEdge(source.id, attachment.start, destination, stationsByPosition[destination], distance)
            }
            val next = exits.filter { it in rails }
            if (next.size != 1) return RailEdge(source.id, attachment.start, current, null, distance)
            previous = current
            current = next.single()
        }
        return RailEdge(source.id, attachment.start, current, null, distance)
    }

    private fun rebuildAttachmentIndex(instance: Instance?) {
        attachmentCandidatesByRail.clear()
        attachmentsByRail.clear()
        stations.values.forEach { station ->
            stationStarts(station.position).forEach { start ->
                attachmentCandidatesByRail.getOrPut(start, ::mutableListOf) += StationAttachment(station.id, start)
            }
        }
        if (instance != null) refreshAttachments(instance, attachmentCandidatesByRail.keys)
    }

    /** Update only station starts touched by a world edit; this is O(A), not O(S). */
    private fun refreshAttachments(instance: Instance, positions: Iterable<BlockVec>) {
        positions.forEach { position ->
            val candidates = attachmentCandidatesByRail[position] ?: return@forEach
            attachmentsByRail.remove(position)
            val block = instance.getBlock(position)
            if (!isRail(block)) return@forEach
            candidates.filter { attachment ->
                stations[attachment.stationId]?.position in railExits(position, block)
            }.takeIf { it.isNotEmpty() }?.let { attachmentsByRail[position] = it.toMutableList() }
        }
    }

    private fun stationStarts(position: BlockVec): List<BlockVec> = directions.flatMap { direction ->
        verticalOffsets.map { y -> offset(position, direction, y) }
    }

    private fun nearbyRailPositions(position: BlockVec): Set<BlockVec> = buildSet {
        add(position)
        directions.forEach { direction -> verticalOffsets.forEach { y -> add(offset(position, direction, y)) } }
    }

    internal fun railExits(position: BlockVec, block: Block): List<BlockVec> {
        fun exit(x: Int, y: Int, z: Int) = BlockVec(position.blockX() + x, position.blockY() + y, position.blockZ() + z)
        return when (block.getProperty("shape")) {
            "north_south" -> listOf(exit(0, 0, -1), exit(0, 0, 1))
            "east_west" -> listOf(exit(-1, 0, 0), exit(1, 0, 0))
            "ascending_east" -> listOf(exit(-1, 0, 0), exit(1, 1, 0))
            "ascending_west" -> listOf(exit(1, 0, 0), exit(-1, 1, 0))
            "ascending_north" -> listOf(exit(0, 0, 1), exit(0, 1, -1))
            "ascending_south" -> listOf(exit(0, 0, -1), exit(0, 1, 1))
            "south_east" -> listOf(exit(0, 0, 1), exit(1, 0, 0))
            "south_west" -> listOf(exit(0, 0, 1), exit(-1, 0, 0))
            "north_east" -> listOf(exit(0, 0, -1), exit(1, 0, 0))
            "north_west" -> listOf(exit(0, 0, -1), exit(-1, 0, 0))
            else -> emptyList()
        }
    }

    private fun offset(position: BlockVec, direction: BlockVec, y: Int = 0) = BlockVec(
        position.blockX() + direction.blockX(),
        position.blockY() + y,
        position.blockZ() + direction.blockZ(),
    )

    private fun isRail(block: Block): Boolean = block.key() in railKeys

    private fun clearTopology() {
        components.clear()
        componentByRail.clear()
        edges.clear()
        edgesByStart.clear()
        edgesByStation.clear()
    }

    private fun removeComponent(id: Int) {
        val component = components.remove(id) ?: return
        component.rails.forEach(componentByRail::remove)
        component.edges.forEach(::removeEdge)
    }

    private fun addEdges(newEdges: List<RailEdge>) {
        newEdges.forEach { edge ->
            edges += edge
            edgesByStart[edge.start] = edge
            edgesByStation.getOrPut(edge.stationId, ::mutableListOf) += edge
            edge.destinationId?.let { edgesByStation.getOrPut(it, ::mutableListOf) += edge }
        }
    }

    private fun removeEdge(edge: RailEdge) {
        edges.remove(edge)
        edgesByStart.remove(edge.start, edge)
        edgesByStation[edge.stationId]?.remove(edge)
        edge.destinationId?.let { edgesByStation[it]?.remove(edge) }
    }

    private fun activeInstance(): Instance? = MinecraftServer.getInstanceManager().instances.firstOrNull()

    private fun load() {
        stations.clear()
        stationsByPosition.clear()
        clearTopology()
        nextStationId = 1
        if (!Files.exists(path)) return
        runCatching {
            Files.newBufferedReader(path).use { reader ->
                val root = JsonParser.parseReader(reader).asJsonObject
                nextStationId = root.get("nextStationId")?.asInt?.coerceAtLeast(1) ?: 1
                root.getAsJsonArray("stations")?.forEach { element ->
                    val json = element.asJsonObject
                    val id = json.get("id")?.asInt ?: return@forEach
                    val position = positionFromJson(json) ?: return@forEach
                    if (id <= 0 || id in stations || position in stationsByPosition) return@forEach
                    val station = TrainStation(id, position, json.get("tier")?.asInt?.coerceIn(0, 3) ?: 0, json.get("banned")?.asBoolean ?: false)
                    stations[id] = station
                    stationsByPosition[position] = id
                    nextStationId = max(nextStationId, id + 1)
                }
            }
        }.onFailure {
            System.err.println("[Nodes] Failed to load trains from $path: ${it.message}")
            stations.clear()
            stationsByPosition.clear()
            nextStationId = 1
        }
    }

    /** Topology edits may arrive in bursts; persist their final coalesced state once. */
    private fun scheduleSave() {
        if (pendingSave != null) return
        pendingSave = MinecraftServer.getSchedulerManager().buildTask {
            pendingSave = null
            save()
        }.delay(TaskSchedule.tick(20)).schedule()
    }

    private fun save() {
        if (!::path.isInitialized) return
        val root = JsonObject().also { it.addProperty("nextStationId", nextStationId) }
        root.add(
            "stations",
            JsonArray().also { array ->
                stations.values.forEach { station ->
                    array.add(
                        JsonObject().also { json ->
                            json.addProperty("id", station.id)
                            json.addProperty("x", station.position.blockX())
                            json.addProperty("y", station.position.blockY())
                            json.addProperty("z", station.position.blockZ())
                            json.addProperty("tier", station.tier)
                            json.addProperty("banned", station.banned)
                        },
                    )
                }
            },
        )
        root.add(
            "edges",
            JsonArray().also { array ->
                edges.forEach { edge ->
                    array.add(
                        JsonObject().also { json ->
                            json.addProperty("station", edge.stationId)
                            json.add("start", positionToJson(edge.start))
                            json.add("end", positionToJson(edge.end))
                            edge.destinationId?.let { json.addProperty("destination", it) } ?: json.add("destination", null)
                            json.addProperty("distance", edge.distance)
                        },
                    )
                }
            },
        )
        val parent = path.parent ?: Path.of(".")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "trains-", ".json.tmp")
        try {
            Files.writeString(temporary, GsonBuilder().create().toJson(root))
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun positionToJson(position: BlockVec) = JsonObject().also {
        it.addProperty("x", position.blockX())
        it.addProperty("y", position.blockY())
        it.addProperty("z", position.blockZ())
    }

    private fun positionFromJson(json: JsonObject): BlockVec? {
        val x = json.get("x")?.asInt ?: return null
        val y = json.get("y")?.asInt ?: return null
        val z = json.get("z")?.asInt ?: return null
        return BlockVec(x, y, z)
    }

    private fun progressBar(progress: Double): String {
        val complete = (progress.coerceIn(0.0, 1.0) * 10).toInt()
        return "[${"|".repeat(complete)}${".".repeat(10 - complete)}]"
    }
}
