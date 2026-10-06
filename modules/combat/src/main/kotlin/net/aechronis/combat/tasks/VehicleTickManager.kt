package net.aechronis.combat.tasks

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Car
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.objects.VehicleRegistry
import net.aechronis.combat.objects.VehicleSeatRole
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.withCombatAttribution
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.Damage
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.instance.Instance
import net.minestom.server.particle.Particle
import net.minestom.server.timer.TaskSchedule
import kotlin.math.abs
import kotlin.math.ceil

object VehicleTickManager {
    private const val IMPACT_COOLDOWN_MS = 700L
    private const val MIN_IMPACT_SPEED = 0.08
    private const val MAX_IMPACT_DAMAGE = 8F
    private const val VEHICLE_INTERACTION_DISTANCE = 3.0
    private const val LOOK_BROAD_PHASE_MARGIN = 1.0e-9

    private data class ImpactKey(
        val vehicle: Entity,
        val player: Player,
    )

    private data class VehiclePose(
        val position: Pos,
        val roll: Float,
        val instance: Instance?,
    )

    private val previousVehiclePoses = HashMap<Entity, VehiclePose>()
    private val lastImpacts = HashMap<ImpactKey, Long>()
    private val collisionIndex = VehicleCollisionIndex()

    val playerLookingAtVehicle = HashMap<Player, Vehicle>()
    val playerLookingAtEntity = HashMap<Player, Entity>()

    fun start() {
        ModuleScheduler
            .buildTask {
                Vehicle.reconcileOccupants()

                // tick occupied vehicles
                for (ride in VehicleRegistry.rides().filter { it.role == VehicleSeatRole.DRIVER }) {
                    ride.vehicle.updateAmmoReload(ride.player)
                    ride.vehicle.onTick(ride.player)
                }

                for (runtime in VehicleRegistry.all()) {
                    if (VehicleRegistry.driverOf(runtime.entity) == null) {
                        runtime.vehicle.onUnoccupiedTick(runtime.entity)
                    }
                }

                val vehicles = VehicleRegistry.all()
                vehicles.forEach {
                    it.updateAnimatedParts()
                    it.updateCollisionHitbox()
                }
                val vehicleLookIndex = prepareVehicleLookIndex(vehicles.map { it.entity to it.vehicle })
                val activeEntities = vehicles.map { it.entity }.toSet()
                collisionIndex.rebuild(MinecraftServer.getConnectionManager().onlinePlayers)

                // Clients collide with the shulkers. Resolve overlaps/moving impacts
                // against those same cubes, independently of interaction/damage hitboxes.
                for (runtime in vehicles) {
                    val previous = previousVehiclePoses[runtime.entity]?.takeIf { it.instance === runtime.entity.instance }
                    handlePlayerCollisions(runtime.entity, runtime.vehicle, previous, collisionIndex)
                }
                previousVehiclePoses.keys.removeIf { it !in activeEntities }
                for (runtime in vehicles) {
                    previousVehiclePoses[runtime.entity] =
                        VehiclePose(runtime.entity.position, runtime.vehicle.hitboxRoll(runtime.entity), runtime.entity.instance)
                }
                lastImpacts.keys.removeIf { it.vehicle !in activeEntities }

                // render hitboxes for all vehicles
                if (Hitbox.viewingHitboxes.isNotEmpty()) {
                    for (runtime in vehicles) {
                        val entity = runtime.entity
                        val vehicle = runtime.vehicle
                        val pos = entity.position
                        vehicle.hitbox.render(
                            entity.instance ?: continue,
                            pos,
                            pos.yaw,
                            pos.pitch,
                            0f,
                            Particle.FLAME,
                            0.3,
                        )
                    }
                }

                // check if players are looking at vehicles and spawn fake blocks around them
                // see modelmanager
                for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                    // skip players already in a vehicle
                    if (VehicleRegistry.driver(player) != null) {
                        playerLookingAtVehicle.remove(player)
                        playerLookingAtEntity.remove(player)
                        continue
                    }

                    val instance = player.instance
                    if (instance == null) {
                        playerLookingAtVehicle.remove(player)
                        playerLookingAtEntity.remove(player)
                        continue
                    }

                    // raycast to check if looking at a vehicle
                    val eyePos = player.position.add(0.0, player.eyeHeight, 0.0)
                    val target =
                        findLookedAtVehicle(
                            instance,
                            eyePos,
                            eyePos.direction().mul(VEHICLE_INTERACTION_DISTANCE),
                            vehicleLookIndex,
                        )

                    if (target != null) {
                        playerLookingAtEntity[player] = target.entity
                        playerLookingAtVehicle[player] = target.vehicle
                    } else {
                        playerLookingAtVehicle.remove(player)
                        playerLookingAtEntity.remove(player)
                    }
                }
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }

    internal class VehicleLookCandidate(
        val entity: Entity,
        val vehicle: Vehicle,
        val position: Pos,
        val boundingRadius: Double,
        val hitbox: Hitbox.Prepared,
    ) {
        var queryStamp = 0L
    }

    internal class VehicleLookIndex(
        candidates: Iterable<VehicleLookCandidate>,
    ) {
        private class InstanceBuckets {
            val cells = HashMap<Long, MutableList<VehicleLookCandidate>>()
            val oversized = ArrayList<VehicleLookCandidate>()
        }

        private val instances = HashMap<Instance, InstanceBuckets>()
        private var query = 0L

        init {
            for (candidate in candidates) {
                val instance = candidate.entity.instance ?: continue
                val buckets = instances.getOrPut(instance, ::InstanceBuckets)
                val radius = candidate.boundingRadius
                val minX = cell(candidate.position.x - radius)
                val maxX = cell(candidate.position.x + radius)
                val minZ = cell(candidate.position.z - radius)
                val maxZ = cell(candidate.position.z + radius)
                val coveredCells = (maxX.toLong() - minX + 1L) * (maxZ.toLong() - minZ + 1L)

                if (coveredCells > MAX_CELLS_PER_VEHICLE) {
                    buckets.oversized.add(candidate)
                    continue
                }

                for (x in minX..maxX) {
                    for (z in minZ..maxZ) {
                        buckets.cells.getOrPut(cellKey(x, z), ::ArrayList).add(candidate)
                    }
                }
            }
        }

        fun findClosest(
            instance: Instance,
            origin: Pos,
            vector: Vec,
        ): VehicleLookCandidate? {
            var closest: VehicleLookCandidate? = null
            var closestDistance = Double.POSITIVE_INFINITY
            val vectorLength = vector.length()

            forEachCandidate(instance, origin, vector) { candidate ->
                if (candidate.entity.instance !== instance) return@forEachCandidate

                val dx = origin.x - candidate.position.x
                val dy = origin.y - candidate.position.y
                val dz = origin.z - candidate.position.z
                val reach = vectorLength + candidate.boundingRadius + LOOK_BROAD_PHASE_MARGIN
                if (dx * dx + dy * dy + dz * dz > reach * reach) return@forEachCandidate

                val distance =
                    candidate.hitbox.firstIntersection(
                        origin,
                        vector,
                        vectorLength,
                    ) ?: return@forEachCandidate
                if (distance < closestDistance) {
                    closest = candidate
                    closestDistance = distance
                }
            }

            return closest
        }

        internal fun candidateCount(
            instance: Instance,
            origin: Pos,
            vector: Vec,
        ): Int {
            var count = 0
            forEachCandidate(instance, origin, vector) { count += 1 }
            return count
        }

        private inline fun forEachCandidate(
            instance: Instance,
            origin: Pos,
            vector: Vec,
            action: (VehicleLookCandidate) -> Unit,
        ) {
            val buckets = instances[instance] ?: return
            val currentQuery = ++query

            for (candidate in buckets.oversized) {
                if (candidate.queryStamp == currentQuery) continue
                candidate.queryStamp = currentQuery
                action(candidate)
            }

            val endX = origin.x + vector.x
            val endZ = origin.z + vector.z
            val minX = cell(minOf(origin.x, endX))
            val maxX = cell(maxOf(origin.x, endX))
            val minZ = cell(minOf(origin.z, endZ))
            val maxZ = cell(maxOf(origin.z, endZ))
            for (x in minX..maxX) {
                for (z in minZ..maxZ) {
                    for (candidate in buckets.cells[cellKey(x, z)] ?: continue) {
                        if (candidate.queryStamp == currentQuery) continue
                        candidate.queryStamp = currentQuery
                        action(candidate)
                    }
                }
            }
        }

        companion object {
            private const val CELL_SIZE = 8.0
            private const val MAX_CELLS_PER_VEHICLE = 64L

            private fun cell(value: Double): Int = kotlin.math.floor(value / CELL_SIZE).toInt()

            private fun cellKey(
                x: Int,
                z: Int,
            ): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
        }
    }

    internal fun prepareVehicleLookIndex(vehicles: Iterable<Pair<Entity, Vehicle>>): VehicleLookIndex =
        VehicleLookIndex(
            vehicles.map { (entity, vehicle) ->
                val position = entity.position
                VehicleLookCandidate(
                    entity,
                    vehicle,
                    position,
                    vehicle.hitbox.boundingRadius,
                    vehicle.hitbox.prepare(
                        position,
                        position.yaw,
                        position.pitch,
                        vehicle.hitboxRoll(entity),
                    ),
                )
            },
        )

    internal fun findLookedAtVehicle(
        instance: Instance,
        origin: Pos,
        vector: Vec,
        vehicles: VehicleLookIndex,
    ): VehicleLookCandidate? = vehicles.findClosest(instance, origin, vector)

    fun removePlayer(player: Player) {
        lastImpacts.keys.removeIf { it.player === player }
        collisionIndex.removePlayer(player)
    }

    fun shutdown() {
        previousVehiclePoses.clear()
        lastImpacts.clear()
        playerLookingAtVehicle.clear()
        playerLookingAtEntity.clear()
        collisionIndex.clear()
    }

    private fun handlePlayerCollisions(
        entity: Entity,
        vehicle: Vehicle,
        previousPose: VehiclePose?,
        collisionIndex: VehicleCollisionIndex,
    ) {
        val instance = entity.instance ?: return
        val position = entity.position
        val previousPosition = previousPose?.position
        val movement =
            if (previousPosition == null) {
                Vec.ZERO
            } else {
                Vec(
                    position.x - previousPosition.x,
                    position.y - previousPosition.y,
                    position.z - previousPosition.z,
                )
            }
        val impactSpeed = movement.length()
        val roll = vehicle.hitboxRoll(entity)
        val now = System.currentTimeMillis()
        val shapes = prepareCollisionSweep(vehicle.collisionHitbox, position, roll, previousPosition, previousPose?.roll ?: roll)
        val broadphaseBounds = VehicleCollisionIndex.sweptBounds(shapes) ?: return

        collisionIndex.forEachCandidate(instance, broadphaseBounds) { player ->
            val collision =
                shapes.firstNotNullOfOrNull {
                    it.resolveCollision(player.position, player.boundingBox.relativeStart(), player.boundingBox.relativeEnd())
                } ?: return@forEachCandidate

            player.teleport(collision.position)
            // The top is a walkable surface, not a side impact. Do not launch or
            // damage someone landing on a vehicle or being lifted by its deck.
            if (collision.normal.y > 0.5) return@forEachCandidate
            applyImpactVelocity(player, collision.normal, movement, position)

            if (vehicle is Car || impactSpeed < MIN_IMPACT_SPEED) return@forEachCandidate
            val key = ImpactKey(entity, player)
            if (now - (lastImpacts[key] ?: 0L) < IMPACT_COOLDOWN_MS) return@forEachCandidate
            lastImpacts[key] = now

            // Speed is measured in blocks per tick. Four hearts is an absolute
            // cap, and leaving one HP prevents a vehicle from instantly killing.
            val amount =
                (impactSpeed * 8.0)
                    .toFloat()
                    .coerceIn(0.0F, MAX_IMPACT_DAMAGE)
                    .coerceAtMost((player.health - 1.0F).coerceAtLeast(0.0F))
            if (amount <= 0.0F) return@forEachCandidate

            val driver = VehicleRegistry.driverOf(entity)?.player
            val damage =
                Damage(DamageType.CRAMMING, driver, driver, position, amount)
                    .withCombatAttribution(CombatDamageKind.VEHICLE)
            Combat.applyDamage(player, damage, now)
        }
    }

    internal fun prepareCollisionSweep(
        hitbox: ShulkerHitbox,
        position: Pos,
        roll: Float,
        previousPosition: Pos?,
        previousRoll: Float,
    ): List<ShulkerHitbox.Shape> {
        if (previousPosition == null) return listOf(hitbox.at(position, roll))

        fun angleDelta(
            current: Float,
            previous: Float,
        ): Float = ((current - previous + 180f) % 360f + 360f) % 360f - 180f
        val yawDelta = angleDelta(position.yaw, previousPosition.yaw)
        val pitchDelta = angleDelta(position.pitch, previousPosition.pitch)
        val rollDelta = angleDelta(roll, previousRoll)
        val travel =
            position.distance(previousPosition) +
                hitbox.radius * Math.toRadians((abs(yawDelta) + abs(pitchDelta) + abs(rollDelta)).toDouble())
        val samples = ceil(travel / 0.25).toInt().coerceIn(1, 128)
        return (samples downTo 1).map { sample ->
            if (sample == samples) {
                hitbox.at(position, roll)
            } else {
                val factor = sample.toDouble() / samples
                val pose =
                    Pos(
                        previousPosition.x + (position.x - previousPosition.x) * factor,
                        previousPosition.y + (position.y - previousPosition.y) * factor,
                        previousPosition.z + (position.z - previousPosition.z) * factor,
                        previousPosition.yaw + yawDelta * factor.toFloat(),
                        previousPosition.pitch + pitchDelta * factor.toFloat(),
                    )
                hitbox.at(pose, previousRoll + rollDelta * factor.toFloat())
            }
        }
    }

    private fun applyImpactVelocity(
        player: Player,
        collisionNormal: Vec,
        movement: Vec,
        vehiclePosition: Pos,
    ) {
        // A moving vehicle carries the target in its direction of travel; the
        // collision normal is only a fallback for stationary overlaps.
        val speed = movement.length()
        var direction = Vec(movement.x, 0.0, movement.z)
        if (direction.lengthSquared() < 1.0e-6) direction = Vec(collisionNormal.x, 0.0, collisionNormal.z)
        if (direction.lengthSquared() < 1.0e-6) {
            val away = Vec(player.position.x - vehiclePosition.x, 0.0, player.position.z - vehiclePosition.z)
            direction = if (away.lengthSquared() < 1.0e-6) Vec(0.0, 0.0, 1.0) else away
        }
        direction = direction.normalize()

        val horizontalStrength = (1.5 + speed * 4.0).coerceAtMost(12.0)
        val verticalStrength = (0.7 + speed * 1.2).coerceAtMost(2.5)
        val current = player.velocity
        val velocity =
            Vec(
                current.x * 0.2 + direction.x * horizontalStrength,
                maxOf(current.y * 0.2, verticalStrength),
                current.z * 0.2 + direction.z * horizontalStrength,
            )
        player.velocity = velocity
    }
}
