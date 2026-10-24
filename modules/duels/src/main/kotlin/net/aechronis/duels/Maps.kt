package net.aechronis.duels

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

@Serializable
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
@Serializable
data class MapDef(
    val id: String,
    val name: String,
    val spawns: List<SpawnPos>,
    val voidY: Double = -8.0,
    val icon: String = "grass_block",
) {
    fun spawnFor(team: Int): Pos = spawns[team % spawns.size].pos()
}

/**
 * Maps live in run/duels/maps/<id>/map.json, with an optional Anvil world in <id>/world.
 * A map without a world folder gets a generated stone platform, so the module works out of the box.
 */
object Maps {
    private val dir: Path = Path.of("duels", "maps")
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

    @Volatile
    private var loaded: List<MapDef> = emptyList()

    fun all(): List<MapDef> = loaded

    fun get(id: String): MapDef? = loaded.firstOrNull { it.id == id }

    fun random(): MapDef? = loaded.randomOrNull()

    fun load() {
        Files.createDirectories(dir)
        if (Files.list(dir).use { it.findAny().isEmpty }) writeDefaults()
        loaded =
            Files
                .list(dir)
                .use { dirs ->
                    dirs.toList().mapNotNull { d ->
                        val file = d.resolve("map.json")
                        if (!Files.isRegularFile(file)) return@mapNotNull null
                        runCatching { json.decodeFromString<MapDef>(Files.readString(file)) }
                            .onFailure { System.err.println("Bad duel map $file: ${it.message}") }
                            .getOrNull()
                            ?.takeIf { it.spawns.isNotEmpty() }
                    }
                }.sortedBy { it.id }
    }

    fun createInstance(map: MapDef): InstanceContainer {
        val world = dir.resolve(map.id).resolve("world")
        val loader = if (Files.isDirectory(world)) AnvilLoader(world, DimensionType.OVERWORLD.key()) else null
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

    private fun writeDefaults() {
        listOf(
            MapDef(
                "flatlands",
                "Flatlands",
                listOf(SpawnPos(-15.5, 64.0, 0.5, -90f), SpawnPos(15.5, 64.0, 0.5, 90f)),
                icon = "grass_block",
            ),
            MapDef("pit", "The Pit", listOf(SpawnPos(-7.5, 64.0, 0.5, -90f), SpawnPos(7.5, 64.0, 0.5, 90f)), icon = "stone"),
        ).forEach {
            Files.createDirectories(dir.resolve(it.id))
            Files.writeString(dir.resolve(it.id).resolve("map.json"), json.encodeToString(MapDef.serializer(), it))
        }
    }
}
