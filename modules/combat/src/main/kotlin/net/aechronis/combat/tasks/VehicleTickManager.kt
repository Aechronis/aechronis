package net.aechronis.combat.tasks

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Car
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.withCombatAttribution
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.watchdog.Watchdog
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

    private val previousVehiclePositions = HashMap<Entity, Pos>()
    private val lastImpacts = HashMap<ImpactKey, Long>()
    private val collisionIndex = VehicleCollisionIndex()

    val playerLookingAtVehicle = HashMap<Player, Vehicle>()
    val playerLookingAtEntity = HashMap<Player, Entity>()

    fun start() {
        ModuleScheduler
            .buildTask {
                Vehicle.reconcileOccupants()

                // tick occupied vehicles
                for ((player, vehicle) in Vehicle.playerVehicle.toList()) {
                    vehicle.onTick(player)
                }

                for ((entity, vehicle) in Vehicle.entityVehicle.toList()) {
                    if (Vehicle.playerVehicleEntity.values.none { it === entity }) {
                        vehicle.onUnoccupiedTick(entity)
                    }
                }

                val vehicles = Vehicle.entityVehicle.toList()
                val vehicleLookIndex = prepareVehicleLookIndex(vehicles)
                val activeEntities = vehicles.map { (entity, _) -> entity }.toSet()
                collisionIndex.rebuild(MinecraftServer.getConnectionManager().onlinePlayers)

                // Keep players outside every vehicle model and handle moving impacts.
                for ((entity, vehicle) in vehicles) {
                    handlePlayerCollisions(entity, vehicle, previousVehiclePositions[entity], collisionIndex)
                }
                previousVehiclePositions.keys.removeIf { it !in activeEntities }
                for ((entity, _) in vehicles) previousVehiclePositions[entity] = entity.position
                lastImpacts.keys.removeIf { it.vehicle !in activeEntities }

                // render hitboxes for all vehicles
                if (Hitbox.viewingHitboxes.isNotEmpty()) {
                    for ((entity, vehicle) in vehicles) {
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
                    if (Vehicle.playerVehicle[player] != null) {
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
        previousVehiclePositions.clear()
        lastImpacts.clear()
        playerLookingAtVehicle.clear()
        playerLookingAtEntity.clear()
        collisionIndex.clear()
    }

    private fun handlePlayerCollisions(
        entity: Entity,
        vehicle: Vehicle,
        previousPosition: Pos?,
        collisionIndex: VehicleCollisionIndex,
    ) {
        val instance = entity.instance ?: return
        val position = entity.position
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
        val broadphaseBounds = VehicleCollisionIndex.sweptBounds(vehicle, position, previousPosition, roll)

        collisionIndex.forEachCandidate(instance, broadphaseBounds) { player ->
            fun collisionAt(checkPosition: Pos) =
                vehicle.hitbox.resolveCollision(
                    checkPosition,
                    position.yaw,
                    position.pitch,
                    roll,
                    player.position,
                    player.boundingBox.relativeStart(),
                    player.boundingBox.relativeEnd(),
                )

            // Fast vehicles can move through a player's current position in one
            // tick, so sample the swept path when the final position is clear.
            var collision = collisionAt(position)
            if (collision == null && previousPosition != null) {
                val samples = ceil(impactSpeed).toInt().coerceIn(1, 32)
                for (sample in samples - 1 downTo 1) {
                    val factor = sample.toDouble() / samples
                    val samplePosition =
                        Pos(
                            previousPosition.x + movement.x * factor,
                            previousPosition.y + movement.y * factor,
                            previousPosition.z + movement.z * factor,
                            position.yaw,
                            position.pitch,
                        )
                    collision = collisionAt(samplePosition)
                    if (collision != null) break
                }
            }
            collision ?: return@forEachCandidate

            player.teleport(collision.position)
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

            val driver =
                Vehicle.playerVehicleEntity.entries
                    .firstOrNull { it.value === entity }
                    ?.key
            val damage =
                Damage(DamageType.CRAMMING, driver, driver, position, amount)
                    .withCombatAttribution(CombatDamageKind.VEHICLE)
            Combat.applyDamage(player, damage, now)
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
        Watchdog.recordKnockback(player, velocity, "vehicle")
    }
}
