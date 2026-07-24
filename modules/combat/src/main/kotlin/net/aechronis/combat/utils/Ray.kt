// Construct a ray with Ray(origin, vector); the vector's length is the max distance checked.
// Call firstEntity() to hit entities, or firstBlock() to hit block collision shapes.

package net.aechronis.combat.utils

import net.minestom.server.collision.Shape
import net.minestom.server.collision.ShapeImpl
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.utils.block.BlockIterator
import kotlin.math.max
import kotlin.math.min

/** Returns the entry point as a fraction of [vector], or null when the segment misses the box. */
internal fun segmentBoxIntersection(
    origin: Point,
    vector: Vec,
    boxStart: Point,
    boxEnd: Point,
): Double? {
    var entry = 0.0
    var exit = 1.0

    fun intersectsAxis(
        start: Double,
        delta: Double,
        minimum: Double,
        maximum: Double,
    ): Boolean {
        if (delta == 0.0) return start in minimum..maximum

        val first = (minimum - start) / delta
        val second = (maximum - start) / delta
        entry = max(entry, min(first, second))
        exit = min(exit, max(first, second))
        return exit >= entry
    }

    if (!intersectsAxis(origin.x(), vector.x(), boxStart.x(), boxEnd.x())) return null
    if (!intersectsAxis(origin.y(), vector.y(), boxStart.y(), boxEnd.y())) return null
    if (!intersectsAxis(origin.z(), vector.z(), boxStart.z(), boxEnd.z())) return null
    return entry
}

/**
 * A ray cast from [origin] along [vector], checking for collisions up to [vector]'s length.
 */
class Ray(
    private val origin: Point,
    private val vector: Vec,
) {
    val distance: Double = vector.length()
    val direction: Vec = if (distance == 0.0) Vec.ZERO else vector.div(distance)

    /** An intersection [t] units along the ray, located at [point], hitting [obj]. */
    data class Hit<T>(
        val t: Double,
        val point: Point,
        val obj: T,
    )

    /**
     * Cast against a single [shape] (a [net.minestom.server.collision.BoundingBox] or an [Entity]),
     * shifted by [offset]. Returns the entry intersection, or null if the ray misses.
     */
    private fun <S : Shape> cast(
        shape: S,
        offset: Point,
    ): Hit<S>? {
        val boxStart = shape.relativeStart().add(offset)
        val boxEnd = shape.relativeEnd().add(offset)
        val fraction = segmentBoxIntersection(origin, vector, boxStart, boxEnd) ?: return null
        return Hit(distance * fraction, origin.add(vector.mul(fraction)), shape)
    }

    /** The closest entity the ray hits, or null if it hits none. */
    fun <E : Entity> firstEntity(entities: Collection<E>): Hit<E>? {
        var best: Hit<E>? = null
        for (entity in entities) {
            val hit = cast(entity, entity.position) ?: continue
            if (best == null || hit.t < best.t) best = hit
        }
        return best
    }

    /** The closest block collision shape the ray hits, or null if it hits none. */
    fun firstBlock(instance: Instance): Hit<Block>? {
        if (distance == 0.0) {
            return firstBlockAt(
                instance,
                Vec(origin.blockX().toDouble(), origin.blockY().toDouble(), origin.blockZ().toDouble()),
            )
        }

        val iterator = BlockIterator(origin.asVec(), direction, 0.0, distance)
        while (iterator.hasNext()) {
            val pos = iterator.next()
            firstBlockAt(instance, pos)?.let { return it }
        }
        return null
    }

    private fun firstBlockAt(
        instance: Instance,
        pos: Point,
    ): Hit<Block>? {
        if (!instance.isChunkLoaded(pos.chunkX(), pos.chunkZ())) return null

        val block = instance.getBlock(pos)
        val hitboxes = (block.registry()?.collisionShape() as? ShapeImpl)?.boundingBoxes() ?: return null

        var best: Hit<Block>? = null
        for (hitbox in hitboxes) {
            val hit = cast(hitbox, pos.asVec()) ?: continue
            if (best == null || hit.t < best.t) best = Hit(hit.t, hit.point, block)
        }
        return best
    }
}
