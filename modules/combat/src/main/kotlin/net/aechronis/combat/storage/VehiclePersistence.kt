package net.aechronis.combat.storage

import com.google.gson.GsonBuilder
import net.aechronis.combat.objects.ArmedVehicle
import net.aechronis.combat.objects.Drone
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Plane
import net.aechronis.combat.objects.Vehicle
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.floor

object VehiclePersistence {
    private const val FORMAT_VERSION = 1
    private const val MIN_WORLD_Y = -64

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var path: Path
    private lateinit var instance: Instance
    private var initialized = false

    private data class PersistedVehicles(
        val version: Int = FORMAT_VERSION,
        val vehicles: List<PersistedVehicle> = emptyList(),
    )

    private data class PersistedVehicle(
        val type: String = "",
        val x: Double = 0.0,
        val y: Double = 0.0,
        val z: Double = 0.0,
        val yaw: Float = 0f,
        val pitch: Float = 0f,
        val health: Float? = null,
        val ammo: Int? = null,
    )

    fun initialize(
        storagePath: Path,
        world: Instance,
    ) {
        check(!initialized) { "Vehicle persistence has already been initialized" }
        path = storagePath
        instance = world
        initialized = true
        load()
    }

    fun saveForShutdown() {
        if (!initialized) return

        Vehicle.entityVehicle.toList().forEach { (entity, vehicle) ->
            if (entity.instance !== instance || vehicle !is Plane) return@forEach
            groundPlane(entity, vehicle)
            vehicle.prepareForShutdown(entity)
        }
        save()
    }

    fun save() {
        if (!initialized) return
        val vehicles =
            Vehicle.entityVehicle.toList().mapNotNull { (entity, vehicle) ->
                if (entity.instance !== instance || vehicle is Drone) return@mapNotNull null
                val position = entity.position
                PersistedVehicle(
                    type = vehicle.name,
                    x = position.x,
                    y = position.y,
                    z = position.z,
                    yaw = position.yaw,
                    pitch = position.pitch,
                    health = Vehicle.entityHealth[entity]?.health,
                    ammo = Vehicle.entityAmmo[entity],
                )
            }
        write(PersistedVehicles(vehicles = vehicles))
    }

    private fun load() {
        if (!Files.exists(path)) return

        val saved =
            try {
                Files.newBufferedReader(path).use { reader -> gson.fromJson(reader, PersistedVehicles::class.java) }
            } catch (exception: Exception) {
                System.err.println("[Combat] Failed to load vehicles from $path: ${exception.message}")
                return
            } ?: return

        if (saved.version != FORMAT_VERSION) {
            System.err.println("[Combat] Unsupported vehicle save version ${saved.version} in $path")
            return
        }

        saved.vehicles.forEach(::restore)
    }

    private fun restore(saved: PersistedVehicle) {
        val vehicle = Item.getFromName(saved.type) as? Vehicle
        if (vehicle == null || vehicle is Drone) {
            System.err.println("[Combat] Ignoring unknown or non-persistent vehicle '${saved.type}'")
            return
        }
        if (!saved.hasFinitePosition()) {
            System.err.println("[Combat] Ignoring vehicle '${saved.type}' with an invalid position")
            return
        }

        val savedPosition = Pos(saved.x, saved.y, saved.z, saved.yaw, saved.pitch)
        val entityPosition =
            if (vehicle is Plane) {
                groundedPosition(savedPosition, vehicle)
                    ?: run {
                        System.err.println("[Combat] Ignoring plane '${saved.type}' because no ground was found below it")
                        return
                    }
            } else {
                savedPosition
            }
        // spawn() accepts an unadjusted placement position while the save stores the entity position.
        val placementPosition = entityPosition.add(0.0, -vehicle.hitbox.getGroundOffset(), 0.0)
        val entity = vehicle.spawn(instance, placementPosition)

        vehicle.health?.let { definition ->
            saved.health?.takeIf { it.isFinite() }?.let { health ->
                Vehicle.entityHealth[entity]?.restore(health.coerceIn(0f, definition.maxHealth))
            }
        }
        (vehicle as? ArmedVehicle)?.let { armedVehicle ->
            saved.ammo?.let { ammo -> Vehicle.entityAmmo[entity] = ammo.coerceIn(0, armedVehicle.maxAmmo) }
        }
    }

    private fun groundPlane(
        entity: net.minestom.server.entity.Entity,
        plane: Plane,
    ) {
        val grounded = groundedPosition(entity.position, plane) ?: return
        entity.teleport(grounded)
    }

    private fun groundedPosition(
        position: Pos,
        vehicle: Vehicle,
    ): Pos? {
        val blockX = floor(position.x).toInt()
        val blockZ = floor(position.z).toInt()
        val chunkX = Math.floorDiv(blockX, 16)
        val chunkZ = Math.floorDiv(blockZ, 16)
        if (!instance.isChunkLoaded(chunkX, chunkZ)) instance.loadChunk(chunkX, chunkZ).join()
        for (blockY in floor(position.y).toInt() downTo MIN_WORLD_Y) {
            if (instance.getBlock(blockX, blockY, blockZ).isSolid) {
                return Pos(
                    position.x,
                    blockY + 1.0 + vehicle.hitbox.getGroundOffset(),
                    position.z,
                    position.yaw,
                    0f,
                )
            }
        }
        return null
    }

    private fun PersistedVehicle.hasFinitePosition(): Boolean =
        x.isFinite() && y.isFinite() && z.isFinite() && yaw.isFinite() && pitch.isFinite()

    private fun write(saved: PersistedVehicles) {
        try {
            val parent = path.parent ?: Path.of(".")
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, "vehicles-", ".json.tmp")
            try {
                Files.newBufferedWriter(temporary).use { writer -> gson.toJson(saved, writer) }
                try {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (exception: Exception) {
            System.err.println("[Combat] Failed to save vehicles to $path: ${exception.message}")
        }
    }
}
