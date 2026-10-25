package net.aechronis.duels

import net.aechronis.server.Server
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.instance.block.Block
import net.minestom.server.world.DimensionType
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs

data class SpawnPos(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
) {
    fun pos() = Pos(x, y, z, yaw, pitch)
}

/** One spawn per team index (wraps around). [icon] is a vanilla material key for the map menu. */
data class MapDef(
    val id: String,
    val name: String,
    val spawns: List<SpawnPos>,
    val voidY: Double = -8.0,
    val icon: String = "grass_block",
    /** Optional Anvil world folder; without one the map gets a generated stone platform. */
    val worldPath: Path? = null,
) {
    fun spawnFor(team: Int): Pos = spawns[team % spawns.size].pos()
}

/** Iteration modules register maps during configure. */
object Maps {
    private val maps = LinkedHashMap<String, MapDef>()

    fun all(): List<MapDef> = synchronized(maps) { maps.values.sortedBy { it.id } }

    fun get(id: String): MapDef? = synchronized(maps) { maps[id] }

    fun random(): MapDef? = all().randomOrNull()

    fun register(vararg toAdd: MapDef) {
        synchronized(maps) {
            require(toAdd.none { it.id in maps }) { "A duel map id is already registered" }
            require(toAdd.none { it.spawns.isEmpty() }) { "A duel map needs at least one spawn" }
            toAdd.forEach { maps[it.id] = it }
        }
    }

    fun createInstance(map: MapDef): InstanceContainer {
        val world = map.worldPath?.takeIf { Files.isDirectory(it) }
        val loader = world?.let { AnvilLoader(it, DimensionType.OVERWORLD.key()) }
        val instance = InstanceContainer(UUID.randomUUID(), Server.fullbrightKey, loader)
        if (loader == null) {
            instance.setGenerator { unit ->
                val start = unit.absoluteStart()
                if (abs(start.blockX()) <= 48 && abs(start.blockZ()) <= 48) unit.modifier().fillHeight(0, 64, Block.STONE)
            }
        }
        MinecraftServer.getInstanceManager().registerInstance(instance)
        return instance
    }
}
