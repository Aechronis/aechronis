package net.aechronis.combat.objects

import net.aechronis.combat.utils.Particles
import net.aechronis.combat.utils.rotatePoint
import net.aechronis.combat.utils.rotatePointInverse
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.particle.Particle

data class HitboxPart(
    val offset: Vec,
    val size: Vec,
    val name: String = "default",
)

// collection of hitbox parts that make up a vehicles collision shape
class Hitbox(
    val parts: List<HitboxPart>,
) {
    // gets the y offset needed to place the vehicle on the ground
    fun getGroundOffset(): Double {
        var lowestY = 0.0
        for (part in parts) {
            val bottomY = part.offset.y - part.size.y
            if (bottomY < lowestY) {
                lowestY = bottomY
            }
        }
        return -lowestY
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
                val blockPos = Pos(corner.x.toInt().toDouble(), corner.y.toInt().toDouble(), corner.z.toInt().toDouble())
                val block = instance.getBlock(blockPos)
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
