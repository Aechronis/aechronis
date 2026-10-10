package net.aechronis.combat.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.aechronis.combat.objects.Boat
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Plane
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.objects.VehicleRegistry
import net.aechronis.server.io.AtomicFiles
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.floor

object VehiclePersistence {
    private const val FORMAT_VERSION = 2
    private val LIFECYCLE_TIMEOUT = Duration.ofSeconds(10)

    private lateinit var path: Path
    private lateinit var instance: Instance
    private var initialized = false

    @Serializable
    private data class PersistedVehicles(
        val version: Int,
        val vehicles: List<PersistedVehicle>,
    )

    @Serializable
    private data class PersistedVehicle(
        val type: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val health: Float? = null,
        val ammo: Int? = null,
        val weaponAmmo: Map<String, Int>? = null,
    )

    @Synchronized
    fun initialize(
        storagePath: Path,
        world: Instance,
    ) {
        check(!initialized) { "Vehicle persistence has already been initialized" }
        path = storagePath
        instance = world
        initialized = true
        load(VehicleLifecycleDeadline.after(LIFECYCLE_TIMEOUT))
    }

    /**
     * Drops the persisted-generation runtime entities after the caller has saved them, then
     * permits the next module generation to initialize persistence again.
     */
    @Synchronized
    fun shutdown() {
        try {
            Vehicle.shutdown()
        } finally {
            initialized = false
        }
    }

    /** Grounds planes and releases riders before player state is captured by other modules. */
    fun prepareForShutdown() {
        if (!initialized) return

        val vehicles = VehicleRegistry.all()
        val planes =
            vehicles.mapNotNull { runtime ->
                val entity = runtime.entity
                val vehicle = runtime.vehicle
                if (entity.instance === instance && vehicle is Plane) entity to vehicle else null
            }
        val deadline = VehicleLifecycleDeadline.after(LIFECYCLE_TIMEOUT)
        preloadChunks(planes.map { (entity, _) -> entity.position }, deadline, "active plane chunks")
        planes.forEach { (entity, vehicle) ->
            groundPlane(entity, vehicle, deadline)
        }
        vehicles.forEach { runtime -> runtime.vehicle.prepareForShutdown(runtime.entity) }
    }

    fun save() {
        if (!initialized) return
        val vehicles =
            VehicleRegistry.all().mapNotNull { runtime ->
                val entity = runtime.entity
                val vehicle = runtime.vehicle
                if (entity.instance !== instance || !vehicle.persistent) return@mapNotNull null
                val position = entity.position
                PersistedVehicle(
                    type = vehicle.name,
                    x = position.x,
                    y = position.y,
                    z = position.z,
                    yaw = position.yaw,
                    pitch = position.pitch,
                    health = runtime.health,
                    ammo = runtime.ammo,
                    weaponAmmo = vehicle.snapshotWeaponAmmo(entity).takeIf { it.isNotEmpty() },
                )
            }
        write(PersistedVehicles(version = FORMAT_VERSION, vehicles = vehicles))
    }

    private fun load(deadline: VehicleLifecycleDeadline) {
        if (!Files.exists(path)) return

        val saved =
            try {
                Json.decodeFromString<PersistedVehicles>(Files.readString(path))
            } catch (exception: Exception) {
                throw IllegalStateException("Failed to load vehicles from $path", exception)
            }

        require(saved.version in 1..FORMAT_VERSION) {
            "Unsupported vehicle save version ${saved.version} in $path"
        }

        val terrainPositions =
            saved.vehicles.flatMap { savedVehicle ->
                if (!savedVehicle.hasFinitePosition()) return@flatMap emptyList()
                val vehicle = Item.getFromName(savedVehicle.type)
                val position = Pos(savedVehicle.x, savedVehicle.y, savedVehicle.z, savedVehicle.yaw, savedVehicle.pitch)
                when (vehicle) {
                    is Plane -> listOf(position)
                    is Boat -> {
                        // Boat.spawn queries water across its hull before creating the
                        // entity. Long ships can span chunks beyond their center chunk.
                        val corners =
                            (
                                vehicle.hitbox.getWorldCorners(position, position.yaw, position.pitch, 0f) +
                                    vehicle.waterHitbox.getWorldCorners(position, position.yaw, position.pitch, 0f)
                            ).flatten()
                                .plus(position.asVec())
                        val minChunkX = Math.floorDiv(floor(corners.minOf { it.x }).toInt(), 16)
                        val maxChunkX = Math.floorDiv(floor(corners.maxOf { it.x }).toInt(), 16)
                        val minChunkZ = Math.floorDiv(floor(corners.minOf { it.z }).toInt(), 16)
                        val maxChunkZ = Math.floorDiv(floor(corners.maxOf { it.z }).toInt(), 16)
                        buildList {
                            for (chunkX in minChunkX..maxChunkX) {
                                for (chunkZ in minChunkZ..maxChunkZ) {
                                    add(Pos(chunkX * 16.0 + 8.0, position.y, chunkZ * 16.0 + 8.0))
                                }
                            }
                        }
                    }
                    else -> emptyList()
                }
            }
        preloadChunks(terrainPositions, deadline, "saved vehicle chunks")
        saved.vehicles.forEach { restore(it, deadline) }
    }

    private fun restore(
        saved: PersistedVehicle,
        deadline: VehicleLifecycleDeadline,
    ) {
        val vehicle = Item.getFromName(saved.type) as? Vehicle
        if (vehicle == null || !vehicle.persistent) {
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
                groundedPosition(savedPosition, vehicle, deadline)
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

        VehicleRegistry.runtime(entity)?.restore(saved.health, saved.ammo)
        vehicle.restoreWeaponAmmo(entity, saved.weaponAmmo, saved.ammo)
    }

    private fun groundPlane(
        entity: net.minestom.server.entity.Entity,
        plane: Plane,
        deadline: VehicleLifecycleDeadline,
    ) {
        val grounded = groundedPosition(entity.position, plane, deadline) ?: return
        entity.teleport(grounded)
    }

    private fun preloadChunks(
        positions: Iterable<Pos>,
        deadline: VehicleLifecycleDeadline,
        description: String,
    ) {
        val chunks =
            positions
                .map { position -> Math.floorDiv(position.blockX(), 16) to Math.floorDiv(position.blockZ(), 16) }
                .distinct()
                .filterNot { (chunkX, chunkZ) -> instance.isChunkLoaded(chunkX, chunkZ) }
        if (chunks.isEmpty()) return

        val loads = chunks.map { (chunkX, chunkZ) -> instance.loadChunk(chunkX, chunkZ) }
        deadline.await(CompletableFuture.allOf(*loads.toTypedArray()), description)
    }

    private fun groundedPosition(
        position: Pos,
        vehicle: Vehicle,
        deadline: VehicleLifecycleDeadline,
    ): Pos? {
        val blockX = floor(position.x).toInt()
        val blockZ = floor(position.z).toInt()
        val chunkX = Math.floorDiv(blockX, 16)
        val chunkZ = Math.floorDiv(blockZ, 16)
        if (!instance.isChunkLoaded(chunkX, chunkZ)) {
            deadline.await(instance.loadChunk(chunkX, chunkZ), "vehicle chunk ($chunkX, $chunkZ)")
        }
        val dimension = instance.cachedDimensionType
        val topY = floor(position.y).toInt().coerceAtMost(dimension.maxY() - 1)
        for (blockY in topY downTo dimension.minY()) {
            if (instance.getBlock(blockX, blockY, blockZ).solid()) {
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
        AtomicFiles.withTemporaryFile(path) { temporary ->
            try {
                Files.newBufferedWriter(temporary).use { writer -> writer.write(Json.encodeToString(saved)) }
                AtomicFiles.replace(temporary, path, preservePermissions = true)
            } catch (error: Throwable) {
                throw IllegalStateException("Failed to save vehicles to $path", error)
            }
        }
    }
}

internal class VehicleLifecycleDeadline private constructor(
    private val deadlineNanos: Long,
) {
    fun <T> await(
        future: CompletableFuture<T>,
        description: String,
    ): T {
        val remainingNanos = deadlineNanos - System.nanoTime()
        check(remainingNanos > 0L) { "$description did not finish before the vehicle lifecycle deadline" }
        return try {
            future.get(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for $description", error)
        } catch (error: TimeoutException) {
            throw IllegalStateException("$description did not finish before the vehicle lifecycle deadline", error)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    companion object {
        fun after(timeout: Duration): VehicleLifecycleDeadline {
            require(!timeout.isNegative && !timeout.isZero) { "Vehicle lifecycle timeout must be positive" }
            return VehicleLifecycleDeadline(System.nanoTime() + timeout.toNanos())
        }
    }
}
