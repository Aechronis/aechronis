package net.aechronis.combat.tasks

import net.aechronis.combat.objects.Boat
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.objects.VehicleRegistry
import net.aechronis.combat.utils.Ray
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

internal data class VehicleTarget(
    val entity: Entity,
    val vehicle: Vehicle,
    val stationIndex: Int?,
)

/** Owns vehicle interaction queries and each player's latest complete target. */
internal object VehicleInteractionTracker {
    const val INTERACTION_DISTANCE = 3.0
    private const val LOOK_BROAD_PHASE_MARGIN = 1.0e-9

    private val targets = HashMap<Player, VehicleTarget>()

    operator fun get(player: Player): VehicleTarget? = targets[player]

    fun update(
        players: Collection<Player>,
        vehicles: VehicleLookIndex,
    ) {
        for (player in players) {
            if (VehicleRegistry.ride(player) != null) {
                targets.remove(player)
                continue
            }

            val instance = player.instance
            if (instance == null) {
                targets.remove(player)
                continue
            }

            val eye = player.position.add(0.0, player.eyeHeight, 0.0)
            val target = findLookedAtVehicle(instance, eye, eye.direction().mul(INTERACTION_DISTANCE), vehicles)
            if (target == null) {
                targets.remove(player)
            } else {
                targets[player] = target
            }
        }
    }

    fun removePlayer(player: Player) {
        targets.remove(player)
    }

    fun clear() {
        targets.clear()
    }

    internal class VehicleLookCandidate(
        val entity: Entity,
        val vehicle: Vehicle,
        val position: Pos,
        val boundingRadius: Double,
        val hitbox: Hitbox.Prepared,
        val stationIndex: Int? = null,
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
            val blockingDistance = Ray(origin, vector).firstBlock(instance)?.t ?: Double.POSITIVE_INFINITY
            val stationHits = HashMap<Entity, Pair<VehicleLookCandidate, Double>>()

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
                if (distance > blockingDistance + LOOK_BROAD_PHASE_MARGIN) return@forEachCandidate
                if (candidate.stationIndex != null) {
                    val previous = stationHits[candidate.entity]
                    if (previous == null || distance < previous.second) stationHits[candidate.entity] = candidate to distance
                }
                if (distance < closestDistance) {
                    closest = candidate
                    closestDistance = distance
                }
            }

            // Coarse hull boxes can overlap a turret. Within the closest boat, a
            // weapon actually intersected by the ray takes precedence over boarding.
            return closest?.let { stationHits[it.entity]?.first ?: it }
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
            vehicles.flatMap { (entity, vehicle) ->
                val position = entity.position
                buildList {
                    add(
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
                        ),
                    )
                    if (vehicle is Boat) {
                        for (target in vehicle.weaponInteractionTargets(entity)) {
                            add(
                                VehicleLookCandidate(
                                    entity,
                                    vehicle,
                                    target.position,
                                    target.hitbox.boundingRadius,
                                    target.hitbox.prepare(target.position, target.position.yaw, target.position.pitch, 0f),
                                    target.stationIndex,
                                ),
                            )
                        }
                    }
                }
            },
        )

    internal fun findLookedAtVehicle(
        instance: Instance,
        origin: Pos,
        vector: Vec,
        vehicles: VehicleLookIndex,
    ): VehicleTarget? =
        vehicles.findClosest(instance, origin, vector)?.let { candidate ->
            VehicleTarget(candidate.entity, candidate.vehicle, candidate.stationIndex)
        }
}
