package net.aechronis.combat.objects

import net.minestom.server.coordinate.Vec
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Builds vehicle player-collision geometry from model-space surfaces, solids, and rails.
 * Coordinates use the item-model origin at (8, 8, 8), with X/Z flipped into vehicle space.
 * Cube sizes and rail thicknesses are in world blocks, independent of model scale.
 */
class CollisionBuilder(
    private val scale: Vec,
) {
    private val parts = ArrayList<ShulkerHitboxPart>()

    fun build(): ShulkerHitbox = ShulkerHitbox(parts.distinct())

    private fun point(point: Vec): Vec = Vec((8.0 - point.x) * scale.x / 16, (point.y - 8.0) * scale.y / 16, (8.0 - point.z) * scale.z / 16)

    /** Rectangular treads and landings keep their exact edges and top height. */
    fun surface(
        minX: Double,
        maxX: Double,
        minZ: Double,
        maxZ: Double,
        top: Double,
        maxCubeSize: Double = 3.0,
    ) {
        val width = (maxX - minX) * scale.x / 16
        val length = (maxZ - minZ) * scale.z / 16
        val side = minOf(3.0, maxCubeSize, width, length).coerceAtLeast(0.0625)
        val center = point(Vec((minX + maxX) / 2, top, (minZ + maxZ) / 2)).add(0.0, -side / 2, 0.0)
        for (x in centers(width, side, side / sqrt(2.0))) {
            for (z in centers(length, side, side / sqrt(2.0))) {
                parts.add(ShulkerHitboxPart(center.add(x, 0.0, z), side))
            }
        }
    }

    /**
     * Use large cubes inside a surface and small cubes at its tapered edges. Expanding
     * each cell by sqrt(2) keeps it covered when the vehicle turns but the cubes do not.
     * Edge error is bounded by the small boundary cells, not a three-metre cube.
     */
    fun surface(
        outline: List<Pair<Double, Double>>,
        top: Double,
        maxCubeSize: Double = 3.0,
        edgeCubeSize: Double = 0.75,
    ) {
        tileSurface(outline, maxCubeSize, edgeCubeSize) { x, z, side ->
            parts.add(ShulkerHitboxPart(Vec(x, (top - 8) * scale.y / 16 - side / 2, z), side))
        }
    }

    fun solid(
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
        maxCubeSize: Double = 3.0,
    ) {
        val width = (maxX - minX) * scale.x / 16
        val height = (maxY - minY) * scale.y / 16
        val length = (maxZ - minZ) * scale.z / 16
        val side = minOf(3.0, maxCubeSize, width, height, length).coerceAtLeast(0.0625)
        val center = point(Vec((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2))
        for (x in centers(width, side, side / sqrt(2.0))) {
            for (y in centers(height, side, side)) {
                for (z in centers(length, side, side / sqrt(2.0))) {
                    parts.add(ShulkerHitboxPart(center.add(x, y, z), side))
                }
            }
        }
    }

    fun column(
        outline: List<Pair<Double, Double>>,
        bottom: Double,
        top: Double,
        maxCubeSize: Double = 3.0,
    ) {
        val height = (top - bottom) * scale.y / 16
        tileSurface(outline, min(maxCubeSize, height)) { x, z, side ->
            for (y in centers(height, side, side)) {
                parts.add(ShulkerHitboxPart(Vec(x, ((bottom + top) / 2 - 8) * scale.y / 16 + y, z), side))
            }
        }
    }

    /** A continuous chain follows the actual rail, including sloped stair handrails. */
    fun rail(
        from: Vec,
        to: Vec,
        thickness: Double = 0.3,
    ) {
        val start = point(from)
        val end = point(to)
        val distance = start.distance(end)
        val steps = ceil(distance / thickness).toInt().coerceAtLeast(1)
        for (step in 0..steps) {
            parts.add(ShulkerHitboxPart(start.add(end.sub(start).mul(step.toDouble() / steps)), thickness))
        }
    }

    private fun centers(
        length: Double,
        side: Double,
        spacing: Double,
    ): List<Double> {
        val span = max(0.0, length - side)
        val steps = ceil(span / spacing).toInt()
        return if (steps == 0) listOf(0.0) else (0..steps).map { -span / 2 + span * it / steps }
    }

    private fun tileSurface(
        outline: List<Pair<Double, Double>>,
        maxCubeSize: Double,
        edgeCubeSize: Double = 0.75,
        add: (Double, Double, Double) -> Unit,
    ) {
        require(outline.size >= 3)
        val polygon = outline.map { (x, z) -> (8 - x) * scale.x / 16 to (8 - z) * scale.z / 16 }
        val maxCell = min(3.0, maxCubeSize) / sqrt(2.0)
        val minCell = min(edgeCubeSize / sqrt(2.0), maxCell)

        fun signedDistance(
            x: Double,
            z: Double,
        ): Double {
            var inside = false
            var distance = Double.POSITIVE_INFINITY
            for (i in polygon.indices) {
                val (ax, az) = polygon[i]
                val (bx, bz) = polygon[(i + 1) % polygon.size]
                if ((az > z) != (bz > z) && x < (bx - ax) * (z - az) / (bz - az) + ax) inside = !inside
                val dx = bx - ax
                val dz = bz - az
                val squared = dx * dx + dz * dz
                val t = if (squared == 0.0) 0.0 else (((x - ax) * dx + (z - az) * dz) / squared).coerceIn(0.0, 1.0)
                distance = min(distance, hypot(x - ax - t * dx, z - az - t * dz))
            }
            return if (inside) distance else -distance
        }

        fun tile(
            minX: Double,
            minZ: Double,
            maxX: Double,
            maxZ: Double,
        ) {
            val width = maxX - minX
            val length = maxZ - minZ
            val x = (minX + maxX) / 2
            val z = (minZ + maxZ) / 2
            val distance = signedDistance(x, z)
            if (distance < -hypot(width, length) / 2) return
            val cell = max(width, length)
            if (cell <= maxCell && distance >= cell || cell <= minCell) {
                if (distance >= 0.0) add(x, z, (cell * sqrt(2.0)).coerceAtLeast(0.0625))
            } else if (width >= length) {
                tile(minX, minZ, x, maxZ)
                tile(x, minZ, maxX, maxZ)
            } else {
                tile(minX, minZ, maxX, z)
                tile(minX, z, maxX, maxZ)
            }
        }

        val minX = polygon.minOf { it.first }
        val minZ = polygon.minOf { it.second }
        val width = polygon.maxOf { it.first } - minX
        val length = polygon.maxOf { it.second } - minZ
        val columns = ceil(width / maxCell).toInt()
        val rows = ceil(length / maxCell).toInt()
        // Start with maximum-sized cells. Bisecting the entire surface first can halve
        // every interior cube just because one overall dimension misses a power of two.
        for (column in 0 until columns) {
            for (row in 0 until rows) {
                tile(
                    minX + width * column / columns,
                    minZ + length * row / rows,
                    minX + width * (column + 1) / columns,
                    minZ + length * (row + 1) / rows,
                )
            }
        }
    }
}
