package net.aechronis.combat.objects

import net.aechronis.combat.utils.Particles
import net.aechronis.combat.utils.rotatePoint
import net.aechronis.combat.utils.rotatePointInverse
import net.aechronis.combat.utils.segmentBoxIntersection
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.particle.Particle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class HitboxPart(
    val offset: Vec,
    val size: Vec,
)

// collection of hitbox parts that make up a vehicles collision shape
internal data class HitboxCollision(
    val position: Pos,
    val normal: Vec,
)

class Hitbox(
    val parts: List<HitboxPart>,
) {
    // Resolves an axis-aligned entity bounding box intersecting this oriented hitbox.
    // The returned position is the entity position (rather than its bounding-box centre).
    internal fun resolveCollision(
        position: Pos,
        yaw: Float,
        pitch: Float,
        roll: Float,
        entityPosition: Pos,
        entityBoxStart: Point,
        entityBoxEnd: Point,
        margin: Double = 0.05,
    ): HitboxCollision? {
        val worldCorners = mutableListOf<Vec>()
        for (x in listOf(entityBoxStart.x(), entityBoxEnd.x())) {
            for (y in listOf(entityBoxStart.y(), entityBoxEnd.y())) {
                for (z in listOf(entityBoxStart.z(), entityBoxEnd.z())) {
                    worldCorners += Vec(entityPosition.x + x, entityPosition.y + y, entityPosition.z + z)
                }
            }
        }

        val localCorners =
            worldCorners.map {
                rotatePointInverse(
                    Vec(it.x - position.x, it.y - position.y, it.z - position.z),
                    yaw,
                    pitch,
                    roll,
                )
            }
        val entityMin =
            Vec(
                localCorners.minOf { it.x },
                localCorners.minOf { it.y },
                localCorners.minOf { it.z },
            )
        val entityMax =
            Vec(
                localCorners.maxOf { it.x },
                localCorners.maxOf { it.y },
                localCorners.maxOf { it.z },
            )
        val entityCenter = entityMin.add(entityMax).mul(0.5)
        val entityCenterOffset =
            Vec(
                (entityBoxStart.x() + entityBoxEnd.x()) * 0.5,
                (entityBoxStart.y() + entityBoxEnd.y()) * 0.5,
                (entityBoxStart.z() + entityBoxEnd.z()) * 0.5,
            )

        var best: HitboxCollision? = null
        var bestPenetration = Double.MAX_VALUE
        for (part in parts) {
            val partMin = part.offset.sub(part.size)
            val partMax = part.offset.add(part.size)
            val overlaps =
                doubleArrayOf(
                    min(partMax.x, entityMax.x) - max(partMin.x, entityMin.x),
                    min(partMax.y, entityMax.y) - max(partMin.y, entityMin.y),
                    min(partMax.z, entityMax.z) - max(partMin.z, entityMin.z),
                )
            if (overlaps.any { it <= 0.0 }) continue

            val axis = overlaps.indices.minBy { overlaps[it] }
            val partCenter = part.offset
            val direction =
                when (axis) {
                    0 -> if (entityCenter.x < partCenter.x) -1.0 else 1.0
                    1 -> if (entityCenter.y < partCenter.y) -1.0 else 1.0
                    else -> if (entityCenter.z < partCenter.z) -1.0 else 1.0
                }
            val resolvedCenter =
                when (axis) {
                    0 -> {
                        entityCenter.withX(
                            if (direction <
                                0
                            ) {
                                partMin.x - (entityMax.x - entityCenter.x) - margin
                            } else {
                                partMax.x + (entityCenter.x - entityMin.x) + margin
                            },
                        )
                    }

                    1 -> {
                        entityCenter.withY(
                            if (direction <
                                0
                            ) {
                                partMin.y - (entityMax.y - entityCenter.y) - margin
                            } else {
                                partMax.y + (entityCenter.y - entityMin.y) + margin
                            },
                        )
                    }

                    else -> {
                        entityCenter.withZ(
                            if (direction <
                                0
                            ) {
                                partMin.z - (entityMax.z - entityCenter.z) - margin
                            } else {
                                partMax.z + (entityCenter.z - entityMin.z) + margin
                            },
                        )
                    }
                }
            val localNormal =
                when (axis) {
                    0 -> Vec(direction, 0.0, 0.0)
                    1 -> Vec(0.0, direction, 0.0)
                    else -> Vec(0.0, 0.0, direction)
                }
            val worldCenter = rotatePoint(resolvedCenter, yaw, pitch, roll).add(position.x, position.y, position.z)
            val worldNormal = rotatePoint(localNormal, yaw, pitch, roll)
            val resolvedPosition =
                Pos(
                    worldCenter.x - entityCenterOffset.x,
                    worldCenter.y - entityCenterOffset.y,
                    worldCenter.z - entityCenterOffset.z,
                    entityPosition.yaw,
                    entityPosition.pitch,
                )
            if (overlaps[axis] < bestPenetration) {
                bestPenetration = overlaps[axis]
                best = HitboxCollision(resolvedPosition, worldNormal)
            }
        }
        return best
    }

    internal fun intersects(
        other: Hitbox,
        position: Pos,
        yaw: Float,
        pitch: Float,
        roll: Float,
        otherPosition: Pos,
        otherYaw: Float,
        otherPitch: Float,
        otherRoll: Float,
    ): Boolean {
        if (parts.isEmpty() || other.parts.isEmpty()) return false
        return parts.any { first ->
            other.parts.any { second ->
                orientedPartsIntersect(first, position, yaw, pitch, roll, second, otherPosition, otherYaw, otherPitch, otherRoll)
            }
        }
    }

    private fun orientedPartsIntersect(
        first: HitboxPart,
        firstPosition: Pos,
        firstYaw: Float,
        firstPitch: Float,
        firstRoll: Float,
        second: HitboxPart,
        secondPosition: Pos,
        secondYaw: Float,
        secondPitch: Float,
        secondRoll: Float,
    ): Boolean {
        fun axes(
            yaw: Float,
            pitch: Float,
            roll: Float,
        ) = listOf(
            rotatePoint(Vec(1.0, 0.0, 0.0), yaw, pitch, roll),
            rotatePoint(Vec(0.0, 1.0, 0.0), yaw, pitch, roll),
            rotatePoint(Vec(0.0, 0.0, 1.0), yaw, pitch, roll),
        )

        fun dot(
            first: Vec,
            second: Vec,
        ) = first.x * second.x + first.y * second.y + first.z * second.z

        fun cross(
            first: Vec,
            second: Vec,
        ) = Vec(
            first.y * second.z - first.z * second.y,
            first.z * second.x - first.x * second.z,
            first.x * second.y - first.y * second.x,
        )

        fun centre(
            part: HitboxPart,
            origin: Pos,
            yaw: Float,
            pitch: Float,
            roll: Float,
        ) = rotatePoint(part.offset, yaw, pitch, roll).add(origin.x, origin.y, origin.z)

        fun radius(
            size: Vec,
            axes: List<Vec>,
            axis: Vec,
        ) = size.x * abs(dot(axis, axes[0])) + size.y * abs(dot(axis, axes[1])) + size.z * abs(dot(axis, axes[2]))

        val firstAxes = axes(firstYaw, firstPitch, firstRoll)
        val secondAxes = axes(secondYaw, secondPitch, secondRoll)
        val firstCentre = centre(first, firstPosition, firstYaw, firstPitch, firstRoll)
        val secondCentre = centre(second, secondPosition, secondYaw, secondPitch, secondRoll)
        val between = secondCentre.sub(firstCentre)
        val testAxes =
            buildList {
                addAll(firstAxes)
                addAll(secondAxes)
                firstAxes.forEach { firstAxis -> secondAxes.forEach { secondAxis -> add(cross(firstAxis, secondAxis)) } }
            }

        return testAxes.all { axis ->
            val lengthSquared = axis.lengthSquared()
            if (lengthSquared < 1.0e-12) {
                true
            } else {
                val normal = axis.mul(1.0 / sqrt(lengthSquared))
                abs(dot(between, normal)) <= radius(first.size, firstAxes, normal) + radius(second.size, secondAxes, normal)
            }
        }
    }

    // gets the y offset needed to place the vehicle on the ground
    fun getGroundOffset(): Double = -getBottomOffset()

    fun getBottomOffset(): Double = parts.minOfOrNull { it.offset.y - it.size.y } ?: 0.0

    fun getTopOffset(): Double = parts.maxOfOrNull { it.offset.y + it.size.y } ?: 0.0

    fun getMaxDistanceFrom(origin: Vec): Double =
        parts.maxOfOrNull { part ->
            val x = abs(part.offset.x - origin.x) + part.size.x
            val y = abs(part.offset.y - origin.y) + part.size.y
            val z = abs(part.offset.z - origin.z) + part.size.z
            sqrt(x * x + y * y + z * z)
        } ?: 0.0

    // local-space centre of the combined bounding box of all parts
    fun getCenterOffset(): Vec {
        if (parts.isEmpty()) return Vec.ZERO

        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE

        for (part in parts) {
            minX = min(minX, part.offset.x - part.size.x)
            maxX = max(maxX, part.offset.x + part.size.x)
            minY = min(minY, part.offset.y - part.size.y)
            maxY = max(maxY, part.offset.y + part.size.y)
            minZ = min(minZ, part.offset.z - part.size.z)
            maxZ = max(maxZ, part.offset.z + part.size.z)
        }

        return Vec((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2)
    }

    // worldspace centre of the hitbox for a given orientation
    fun getWorldCenter(
        position: Pos,
        yaw: Float,
        pitch: Float,
        roll: Float,
    ): Pos {
        val center = rotatePoint(getCenterOffset(), yaw, pitch, roll)
        return Pos(
            position.x + center.x,
            position.y + center.y,
            position.z + center.z,
            position.yaw,
            position.pitch,
        )
    }

    companion object {
        // players viewing hitboxes
        val viewingHitboxes = HashSet<Player>()
    }

    // gets the worldspace corners of all hitbox parts
    fun getWorldCorners(
        position: Pos,
        yaw: Float,
        pitch: Float,
        roll: Float,
    ): List<List<Vec>> =
        parts.map { part ->
            getPartWorldCorners(part, position, yaw, pitch, roll)
        }

    // gets the 8 corners of a hitbox part in worldspace
    private fun getPartWorldCorners(
        part: HitboxPart,
        position: Pos,
        yaw: Float,
        pitch: Float,
        roll: Float,
    ): List<Vec> {
        val corners = mutableListOf<Vec>()
        val signs = listOf(-1.0, 1.0)

        for (sx in signs) {
            for (sy in signs) {
                for (sz in signs) {
                    val localCorner =
                        part.offset.add(
                            Vec(
                                part.size.x * sx,
                                part.size.y * sy,
                                part.size.z * sz,
                            ),
                        )
                    val worldCorner = rotatePoint(localCorner, yaw, pitch, roll)
                    corners.add(Vec(position.x + worldCorner.x, position.y + worldCorner.y, position.z + worldCorner.z))
                }
            }
        }
        return corners
    }

    // checks if a point is inside any hitbox part
    fun containsPoint(
        point: Vec,
        position: Pos,
        yaw: Float,
        pitch: Float,
        roll: Float,
    ): HitboxPart? {
        for (part in parts) {
            if (partContainsPoint(part, point, position, yaw, pitch, roll)) {
                return part
            }
        }
        return null
    }

    // Returns the nearest intersection in world-space units along the segment.
    internal fun firstIntersection(
        origin: Point,
        vector: Vec,
        position: Pos,
        yaw: Float,
        pitch: Float,
        roll: Float,
    ): Double? {
        val localOrigin =
            rotatePointInverse(
                Vec(origin.x() - position.x, origin.y() - position.y, origin.z() - position.z),
                yaw,
                pitch,
                roll,
            )
        val localVector = rotatePointInverse(vector, yaw, pitch, roll)
        val distance = vector.length()

        return parts
            .mapNotNull { part ->
                segmentBoxIntersection(
                    localOrigin,
                    localVector,
                    part.offset.sub(part.size),
                    part.offset.add(part.size),
                )
            }.minOrNull()
            ?.times(distance)
    }

    // checks if a point is inside a specific hitbox part
    private fun partContainsPoint(
        part: HitboxPart,
        point: Vec,
        position: Pos,
        yaw: Float,
        pitch: Float,
        roll: Float,
    ): Boolean {
        // point -> local space
        val relativePoint = Vec(point.x - position.x, point.y - position.y, point.z - position.z)
        val localPoint = rotatePointInverse(relativePoint, yaw, pitch, roll)

        val adjusted = localPoint.sub(part.offset)
        return adjusted.x >= -part.size.x &&
            adjusted.x <= part.size.x &&
            adjusted.y >= -part.size.y &&
            adjusted.y <= part.size.y &&
            adjusted.z >= -part.size.z &&
            adjusted.z <= part.size.z
    }

    // checks for collision with the ground (any blocks)
    // returns the lowest Y coordinate of the hitbox if colliding
    fun checkGroundCollision(
        instance: Instance,
        position: Pos,
        yaw: Float,
        pitch: Float,
        roll: Float,
    ): Boolean {
        val allCorners = getWorldCorners(position, yaw, pitch, roll)
        for (partCorners in allCorners) {
            for (corner in partCorners) {
                val block = instance.getBlock(corner)
                if (!block.isAir) {
                    return true
                }
            }
        }
        return false
    }

    // renders hitbox with particles for debugging
    fun render(
        instance: Instance,
        position: Pos,
        yaw: Float,
        pitch: Float,
        roll: Float,
        particle: Particle = Particle.FLAME,
        spacing: Double = 0.5,
    ) {
        for (part in parts) {
            renderPart(part, instance, position, yaw, pitch, roll, particle, spacing)
        }
    }

    // renders a single hitbox part
    private fun renderPart(
        part: HitboxPart,
        instance: Instance,
        position: Pos,
        yaw: Float,
        pitch: Float,
        roll: Float,
        particle: Particle,
        spacing: Double,
    ) {
        val corners = getPartWorldCorners(part, position, yaw, pitch, roll)

        // (-x,-y,-z), (-x,-y,+z), (-x,+y,-z), (-x,+y,+z), (+x,-y,-z), (+x,-y,+z), (+x,+y,-z), (+x,+y,+z)
        val edges =
            listOf(
                // bottom face
                0 to 1,
                0 to 2,
                1 to 3,
                2 to 3,
                // top face
                4 to 5,
                4 to 6,
                5 to 7,
                6 to 7,
                // vertical edges
                0 to 4,
                1 to 5,
                2 to 6,
                3 to 7,
            )

        for ((i1, i2) in edges) {
            val from = corners[i1]
            val to = corners[i2]
            Particles.particleLine(instance, particle, Pos(from.x, from.y, from.z), Pos(to.x, to.y, to.z), spacing)
        }
    }
}
