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
import java.nio.file.attribute.PosixFileAttributeView
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.floor

object VehiclePersistence {
    private const val FORMAT_VERSION = 1
    private val LIFECYCLE_TIMEOUT = Duration.ofSeconds(10)

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

    fun saveForShutdown() {
        if (!initialized) return

        val planes =
            Vehicle.entityVehicle.toList().mapNotNull { (entity, vehicle) ->
                if (entity.instance === instance && vehicle is Plane) entity to vehicle else null
            }
        val deadline = VehicleLifecycleDeadline.after(LIFECYCLE_TIMEOUT)
        preloadChunks(planes.map { (entity, _) -> entity.position }, deadline, "active plane chunks")
        planes.forEach { (entity, vehicle) ->
            groundPlane(entity, vehicle, deadline)
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

    private fun load(deadline: VehicleLifecycleDeadline) {
        if (!Files.exists(path)) return

        val saved =
            try {
                Files.newBufferedReader(path).use { reader -> gson.fromJson(reader, PersistedVehicles::class.java) }
            } catch (exception: Exception) {
                throw IllegalStateException("Failed to load vehicles from $path", exception)
            } ?: throw IllegalStateException("Vehicle save is empty: $path")

        require(saved.version == FORMAT_VERSION) {
            "Unsupported vehicle save version ${saved.version} in $path"
        }

        val planePositions =
            saved.vehicles.mapNotNull { vehicle ->
                if (!vehicle.hasFinitePosition() || Item.getFromName(vehicle.type) !is Plane) return@mapNotNull null
                Pos(vehicle.x, vehicle.y, vehicle.z, vehicle.yaw, vehicle.pitch)
            }
        preloadChunks(planePositions, deadline, "saved plane chunks")
        saved.vehicles.forEach { restore(it, deadline) }
    }

    private fun restore(
        saved: PersistedVehicle,
        deadline: VehicleLifecycleDeadline,
    ) {
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
        val parent = path.parent ?: Path.of(".")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "vehicles-", ".json.tmp")
        var writeFailure: Throwable? = null
        try {
            Files.newBufferedWriter(temporary).use { writer -> gson.toJson(saved, writer) }
            preservePermissions(path, temporary)
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Throwable) {
            val wrapped = IllegalStateException("Failed to save vehicles to $path", error)
            writeFailure = wrapped
            throw wrapped
        } finally {
            runCatching { Files.deleteIfExists(temporary) }.exceptionOrNull()?.let { cleanupError ->
                writeFailure?.addSuppressed(cleanupError) ?: throw cleanupError
            }
        }
    }

    private fun preservePermissions(
        source: Path,
        target: Path,
    ) {
        if (!Files.exists(source)) return
        val sourceAttributes = Files.getFileAttributeView(source, PosixFileAttributeView::class.java) ?: return
        val targetAttributes = Files.getFileAttributeView(target, PosixFileAttributeView::class.java) ?: return
        targetAttributes.setPermissions(sourceAttributes.readAttributes().permissions())
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
