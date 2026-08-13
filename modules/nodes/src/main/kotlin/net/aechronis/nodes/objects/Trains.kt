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
    private data class Journey(
        val start: BlockVec,
        val task: Task,
    )

    private data class Trace(
        val end: BlockVec,
        val destinationId: Int?,
        val distance: Int,
    )

    private val stations = linkedMapOf<Int, TrainStation>()
    private val stationsByPosition = hashMapOf<BlockVec, Int>()
    private val edges = mutableListOf<RailEdge>()
    private val edgesByStart = hashMapOf<BlockVec, RailEdge>()
    private val journeys = hashMapOf<UUID, Journey>()
    private val directions = listOf(
        BlockVec(0, 0, -1),
        BlockVec(0, 0, 1),
        BlockVec(1, 0, 0),
        BlockVec(-1, 0, 0),
    )
    private var nextStationId = 1
    private var scanTask: Task? = null
    private lateinit var path: Path

    fun initialize(path: Path) {
        this.path = path
        load()
        scanAll(activeInstance())
        scanTask = MinecraftServer.getSchedulerManager()
            .buildTask { scanAll(activeInstance()) }
            .delay(TaskSchedule.seconds(60))
            .repeat(TaskSchedule.seconds(60))
            .schedule()
    }

    fun cleanup() {
        scanTask?.cancel()
        scanTask = null
        journeys.values.forEach { it.task.cancel() }
        journeys.clear()
        save()
    }

    fun allStations(): List<TrainStation> = stations.values.toList()

    fun station(id: Int): TrainStation? = stations[id]

    fun create(position: BlockVec, instance: Instance): Result<TrainStation> = runCatching {
        require(instance.getBlock(position) == Block.GOLD_BLOCK) { "Stations must be created on a gold block" }
        require(position !in stationsByPosition) { "A station already exists at this gold block" }
        TrainStation(nextStationId++, position).also {
            stations[it.id] = it
            stationsByPosition[position] = it.id
            rebuildStationEdges(it, instance)
            save()
        }
    }

    fun remove(id: Int): TrainStation? {
        val station = stations.remove(id) ?: return null
        stationsByPosition.remove(station.position)
        edges.removeAll { it.stationId == id || it.destinationId == id }
        rebuildEdgeIndex()
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
        val count = rebuildStationEdges(station, instance)
        save()
        count
    }

    fun scanAll(instance: Instance?): Int {
        if (instance == null) return 0
        var changed = false
        var count = 0
        stations.values.toList().forEach { station ->
            if (instance.getBlock(station.position) != Block.GOLD_BLOCK) {
                stations.remove(station.id)
                stationsByPosition.remove(station.position)
                edges.removeAll { it.stationId == station.id || it.destinationId == station.id }
                changed = true
            } else {
                val old = edges.filter { it.stationId == station.id }
                val fresh = findEdges(station, instance)
                if (old != fresh) {
                    edges.removeAll { it.stationId == station.id }
                    edges.addAll(fresh)
                    changed = true
                }
                count += fresh.size
            }
        }
        if (changed) {
            rebuildEdgeIndex()
            save()
        }
        return count
    }

    fun edgesFrom(id: Int): List<RailEdge> = edges.filter { it.stationId == id || it.destinationId == id }

    fun startJourney(player: Player, instance: Instance, rail: BlockVec): Result<Unit> = runCatching {
        val edge = edgesByStart[rail] ?: error("No train leaves from this rail")
        val source = stations[edge.stationId] ?: error("Station not found")
        require(!source.banned) { "This station is banned" }
        val destination = edge.destinationId?.let { stations[it] }
        require(destination?.banned != true) { "Destination station is banned" }
        cancel(player)
        val speed = speed(source.tier)
        val duration = max(1L, ceil(edge.distance * 1000.0 / speed).toLong())
        val start = player.position.asBlockVec()
        val startedAt = System.currentTimeMillis()
        lateinit var task: Task
        task = MinecraftServer.getSchedulerManager()
            .buildTask {
                val elapsed = System.currentTimeMillis() - startedAt
                if (!player.isOnline || elapsed >= duration) {
                    journeys.remove(player.uuid)
                    task.cancel()
                    if (player.isOnline) {
                        val target = destination?.position ?: edge.end
                        player.teleport(Pos(target.x() + 0.5, target.y() + 1.0, target.z() + 0.5, player.position.yaw, player.position.pitch))
                        Message.print(player, "Arrived at destination")
                    }
                    return@buildTask
                }
                val progress = elapsed.toDouble() / duration
                Message.announcement(player, "${progressBar(progress)} ${speed.toInt()} blocks/s")
            }.repeat(TaskSchedule.tick(2))
            .schedule()
        journeys[player.uuid] = Journey(start, task)
    }

    fun cancelIfMoved(player: Player, position: BlockVec) {
        val journey = journeys[player.uuid] ?: return
        if (journey.start != position) {
            cancel(player)
            Message.error(player, "Train journey cancelled due to movement")
        }
    }

    fun cancel(player: Player, message: String? = null) {
        journeys.remove(player.uuid)?.task?.cancel()
        message?.let { Message.error(player, it) }
    }

    fun removeAt(position: BlockVec): TrainStation? = stationsByPosition[position]?.let(::remove)

    fun incomeAt(chunkX: Int, chunkZ: Int): Map<Material, Double> = stations.values
        .asSequence()
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

    private fun rebuildStationEdges(station: TrainStation, instance: Instance): Int {
        val fresh = findEdges(station, instance)
        edges.removeAll { it.stationId == station.id }
        edges.addAll(fresh)
        rebuildEdgeIndex()
        return fresh.size
    }

    private fun findEdges(station: TrainStation, instance: Instance): List<RailEdge> = directions
        .mapNotNull { direction ->
            val start = offset(station.position, direction)
            if (!isRail(instance, start)) return@mapNotNull null
            val trace = trace(instance, start, direction)
            RailEdge(station.id, start, trace.end, trace.destinationId, trace.distance)
        }

    private fun trace(instance: Instance, start: BlockVec, initialDirection: BlockVec): Trace {
        var current = start
        var direction = initialDirection
        var distance = 0
        val visited = hashSetOf<BlockVec>()
        while (distance < 10_000 && visited.add(current) && isRail(instance, current)) {
            distance++
            var nextPosition: BlockVec? = null
            var nextDirection: BlockVec? = null
            for (candidateDirection in nextDirections(direction)) {
                for (yOffset in listOf(0, 1, -1)) {
                    val next = offset(current, candidateDirection, yOffset)
                    if (instance.getBlock(next) == Block.GOLD_BLOCK) {
                        return Trace(next, stationsByPosition[next], distance)
                    }
                    if (next !in visited && isRail(instance, next)) {
                        nextPosition = next
                        nextDirection = candidateDirection
                        break
                    }
                }
                if (nextPosition != null) break
            }
            if (nextPosition == null || nextDirection == null) return Trace(current, null, distance)
            current = nextPosition
            direction = nextDirection
        }
        return Trace(current, null, distance)
    }

    private fun nextDirections(direction: BlockVec): List<BlockVec> = when (direction) {
        directions[0] -> listOf(directions[0], directions[2], directions[3])
        directions[1] -> listOf(directions[1], directions[2], directions[3])
        directions[2] -> listOf(directions[2], directions[0], directions[1])
        else -> listOf(directions[3], directions[0], directions[1])
    }

    private fun offset(position: BlockVec, direction: BlockVec, y: Int = 0): BlockVec = BlockVec(
        position.blockX() + direction.blockX(),
        position.blockY() + direction.blockY() + y,
        position.blockZ() + direction.blockZ(),
    )

    private fun isRail(instance: Instance, position: BlockVec): Boolean = instance.getBlock(position) == Block.RAIL

    private fun rebuildEdgeIndex() {
        edgesByStart.clear()
        edges.forEach { edgesByStart[it.start] = it }
    }

    private fun activeInstance(): Instance? = MinecraftServer.getInstanceManager().instances.firstOrNull()

    private fun load() {
        stations.clear()
        stationsByPosition.clear()
        edges.clear()
        edgesByStart.clear()
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
                root.getAsJsonArray("edges")?.forEach { element ->
                    val json = element.asJsonObject
                    val stationId = json.get("station")?.asInt ?: return@forEach
                    val start = json.getAsJsonObject("start")?.let(::positionFromJson) ?: return@forEach
                    val end = json.getAsJsonObject("end")?.let(::positionFromJson) ?: return@forEach
                    val destination = json.get("destination")?.takeUnless { it.isJsonNull }?.asInt
                    val distance = json.get("distance")?.asInt ?: return@forEach
                    if (destination != null && destination !in stations) return@forEach
                    if (distance < 1) return@forEach
                    if (stationId !in stations) return@forEach
                    edges.add(RailEdge(stationId, start, end, destination, distance))
                }
            }
            rebuildEdgeIndex()
        }.onFailure {
            System.err.println("[Nodes] Failed to load trains from $path: ${it.message}")
            stations.clear()
            stationsByPosition.clear()
            edges.clear()
            edgesByStart.clear()
            nextStationId = 1
        }
    }

    private fun save() {
        if (!::path.isInitialized) return
        val root = JsonObject()
        root.addProperty("nextStationId", nextStationId)
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

    private fun positionToJson(position: BlockVec): JsonObject = JsonObject().also {
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
