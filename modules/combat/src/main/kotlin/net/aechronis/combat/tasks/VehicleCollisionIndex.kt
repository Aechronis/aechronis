package net.aechronis.combat.tasks

import net.aechronis.combat.objects.Vehicle
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal class VehicleCollisionIndex {
    data class Bounds(
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double,
    )

    private val buckets = HashMap<Instance, HashMap<Long, MutableList<Player>>>()
    private val candidateQueries = HashMap<Player, Long>()
    private var query = 0L

    fun rebuild(players: Collection<Player>) {
        for (instanceBuckets in buckets.values) {
            for (bucket in instanceBuckets.values) bucket.clear()
        }

        for (player in players) {
            val instance = player.instance ?: continue
            if (Vehicle.playerVehicle[player] != null || Vehicle.passengerVehicle[player] != null) continue

            val box = player.boundingBox
            val start = box.relativeStart()
            val end = box.relativeEnd()
            val minX = player.position.x + start.x()
            val minZ = player.position.z + start.z()
            val maxX = player.position.x + end.x()
            val maxZ = player.position.z + end.z()
            val instanceBuckets = buckets.getOrPut(instance) { HashMap() }

            for (x in cell(minX)..cell(maxX)) {
                for (z in cell(minZ)..cell(maxZ)) {
                    instanceBuckets.getOrPut(cellKey(x, z)) { ArrayList() }.add(player)
                }
            }
        }
    }

    fun forEachCandidate(
        instance: Instance,
        bounds: Bounds,
        action: (Player) -> Unit,
    ) {
        val instanceBuckets = buckets[instance] ?: return
        query += 1
        val currentQuery = query
        for (x in cell(bounds.minX)..cell(bounds.maxX)) {
            for (z in cell(bounds.minZ)..cell(bounds.maxZ)) {
                for (player in instanceBuckets[cellKey(x, z)] ?: continue) {
                    if (candidateQueries.put(player, currentQuery) == currentQuery) continue
                    if (!overlapsY(player, bounds)) continue
                    action(player)
                }
            }
        }
    }

    fun removePlayer(player: Player) {
        candidateQueries.remove(player)
    }

    private fun overlapsY(
        player: Player,
        bounds: Bounds,
    ): Boolean {
        val start = player.boundingBox.relativeStart().y()
        val end = player.boundingBox.relativeEnd().y()
        val minY = player.position.y + start
        val maxY = player.position.y + end
        return minY < bounds.maxY && maxY > bounds.minY
    }

    companion object {
        private const val CELL_SIZE = 16.0

        fun sweptBounds(
            vehicle: Vehicle,
            position: Pos,
            previousPosition: Pos?,
            roll: Float,
        ): Bounds {
            if (vehicle.hitbox.parts.isEmpty()) {
                return Bounds(position.x, position.y, position.z, position.x, position.y, position.z)
            }

            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var minZ = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            var maxZ = -Double.MAX_VALUE

            fun include(position: Pos) {
                for (partCorners in vehicle.hitbox.getWorldCorners(position, position.yaw, position.pitch, roll)) {
                    for (corner in partCorners) {
                        minX = min(minX, corner.x)
                        minY = min(minY, corner.y)
                        minZ = min(minZ, corner.z)
                        maxX = max(maxX, corner.x)
                        maxY = max(maxY, corner.y)
                        maxZ = max(maxZ, corner.z)
                    }
                }
            }

            include(position)
            if (previousPosition != null) include(previousPosition)

            return Bounds(minX - 0.05, minY - 0.05, minZ - 0.05, maxX + 0.05, maxY + 0.05, maxZ + 0.05)
        }

        private fun cell(value: Double): Int = floor(value / CELL_SIZE).toInt()

        private fun cellKey(
            x: Int,
            z: Int,
        ): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
    }
}
