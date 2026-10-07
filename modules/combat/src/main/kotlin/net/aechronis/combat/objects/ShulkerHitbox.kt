package net.aechronis.combat.objects

import net.aechronis.combat.utils.rotatePoint
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A closed shulker cube, centered at [offset] in vehicle-local world units. */
data class ShulkerHitboxPart(
    val offset: Vec,
    val scale: Double,
) {
    init {
        require(offset.x.isFinite() && offset.y.isFinite() && offset.z.isFinite())
        require(scale.isFinite() && scale in 0.0625..3.0) { "Shulker collision scale must be between 0.0625 and 3" }
    }
}

/**
 * Player collision, independent of the vehicle's interaction/damage [Hitbox].
 * Centers follow the vehicle's rotation; Minecraft keeps each cube world-axis aligned.
 */
class ShulkerHitbox(
    parts: List<ShulkerHitboxPart>,
) {
    val parts: List<ShulkerHitboxPart> = parts.toList()
    internal val radius: Double = parts.maxOfOrNull { it.offset.length() + it.scale * sqrt(3.0) / 2 } ?: 0.0

    internal fun at(
        position: Pos,
        roll: Float,
    ): Shape =
        Shape(
            parts.map { part ->
                val center = position.asVec().add(rotatePoint(part.offset, position.yaw, position.pitch, roll))
                val half = Vec(part.scale / 2)
                Box(center.sub(half), center.add(half))
            },
        )

    internal data class Box(
        val min: Vec,
        val max: Vec,
    ) {
        val bottomCenter: Pos get() = Pos((min.x + max.x) / 2, min.y, (min.z + max.z) / 2)
    }

    internal class Shape(
        val boxes: List<Box>,
    ) {
        /** Nearest surface with any part of the player's box in the space above it. */
        fun supportDistance(
            position: Pos,
            relativeStart: Point,
            relativeEnd: Point,
            maxDistance: Double,
        ): Double? {
            val start = position.asVec().add(relativeStart)
            val end = position.asVec().add(relativeEnd)
            var nearest = Double.POSITIVE_INFINITY
            for (box in boxes) {
                if (start.x >= box.max.x || end.x <= box.min.x || start.z >= box.max.z || end.z <= box.min.z) continue
                if (end.y <= box.max.y) continue
                val distance = max(0.0, start.y - box.max.y)
                if (distance < maxDistance && distance < nearest) nearest = distance
            }
            return nearest.takeIf { it.isFinite() }
        }

        /**
         * Resolve against the union, not one cube at a time: adjacent shulkers overlap,
         * and pushing into a neighboring cube would make players oscillate at seams.
         */
        fun resolveCollision(
            position: Pos,
            relativeStart: Point,
            relativeEnd: Point,
        ): HitboxCollision? {
            val start = position.asVec().add(relativeStart)
            val end = position.asVec().add(relativeEnd)

            fun overlaps(
                box: Box,
                axis: Int,
            ): Boolean =
                component(start, axis) < component(box.max, axis) - EPSILON &&
                    component(end, axis) > component(box.min, axis) + EPSILON

            if (boxes.none { box -> (0..2).all { overlaps(box, it) } }) return null

            var bestDistance = Double.POSITIVE_INFINITY
            var bestNormal = Vec.ZERO
            // Prefer the top surface when a corner has equally short escape directions.
            for (axis in listOf(1, 0, 2)) {
                val intervals =
                    boxes
                        .filter { box -> (0..2).all { it == axis || overlaps(box, it) } }
                        .map { box ->
                            (component(box.min, axis) - component(end, axis)) to
                                (component(box.max, axis) - component(start, axis))
                        }.sortedBy { it.first }
                var lower = Double.NaN
                var upper = Double.NaN
                for ((from, to) in intervals) {
                    if (lower.isNaN() || from > upper + EPSILON) {
                        if (lower < -EPSILON && upper > EPSILON) break
                        lower = from
                        upper = to
                    } else {
                        upper = max(upper, to)
                    }
                }
                if (!(lower < -EPSILON && upper > EPSILON)) continue
                val distance = if (upper <= -lower) upper + EPSILON else lower - EPSILON
                if (abs(distance) < abs(bestDistance)) {
                    bestDistance = distance
                    bestNormal =
                        when (axis) {
                            0 -> Vec(if (distance > 0) 1.0 else -1.0, 0.0, 0.0)
                            1 -> Vec(0.0, if (distance > 0) 1.0 else -1.0, 0.0)
                            else -> Vec(0.0, 0.0, if (distance > 0) 1.0 else -1.0)
                        }
                }
            }
            return HitboxCollision(position.add(bestNormal.mul(abs(bestDistance))), bestNormal)
        }

        private fun component(
            point: Point,
            axis: Int,
        ): Double =
            when (axis) {
                0 -> point.x()
                1 -> point.y()
                else -> point.z()
            }
    }

    companion object {
        private const val EPSILON = 1.0e-6

        /**
         * Default physical shape for existing definitions. Overlapping cubes keep the
         * interior joined when their centers rotate while their collision boxes do not.
         * Pass explicit parts to use a simpler hull or a different deck layout.
         */
        fun fromHitbox(hitbox: Hitbox): ShulkerHitbox =
            ShulkerHitbox(
                buildList {
                    for (part in hitbox.parts) {
                        val dimensions = part.size.mul(2.0)
                        require(listOf(dimensions.x, dimensions.y, dimensions.z).all { it.isFinite() && it > 0 }) {
                            "Vehicle collision dimensions must be positive and finite"
                        }
                        val scale = min(3.0, min(dimensions.x, min(dimensions.y, dimensions.z))).coerceAtLeast(0.0625)

                        fun centers(length: Double): List<Double> {
                            val span = max(0.0, length - scale)
                            val steps = ceil(span / (scale / sqrt(3.0))).toInt()
                            require(steps <= 512) { "Use explicit shulker parts for very large vehicle hitboxes" }
                            return if (steps == 0) listOf(0.0) else (0..steps).map { -span / 2 + span * it / steps }
                        }
                        val xs = centers(dimensions.x)
                        val ys = centers(dimensions.y)
                        val zs = centers(dimensions.z)
                        require(size.toLong() + xs.size.toLong() * ys.size * zs.size <= 512) {
                            "Use a simpler physical hitbox: a vehicle may have at most 512 generated shulkers"
                        }
                        for (x in xs) {
                            for (y in ys) {
                                for (z in zs) add(ShulkerHitboxPart(part.offset.add(x, y, z), scale))
                            }
                        }
                    }
                }.distinct(),
            )
    }
}
