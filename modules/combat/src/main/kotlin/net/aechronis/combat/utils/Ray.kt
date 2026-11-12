// Construct a ray with Ray(origin, vector); the vector's length is the max distance checked.
// Call firstEntity() to hit entities, or firstBlock() to hit block collision shapes.

package net.aechronis.combat.utils

import net.minestom.server.collision.Shape
import net.minestom.server.collision.ShapeImpl
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.utils.block.BlockIterator
import kotlin.math.abs
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
    internal val origin: Point,
    internal val vector: Vec,
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
    ): Hit<S>? = hitBox(shape.relativeStart().add(offset), shape.relativeEnd().add(offset), shape)

    /** Casts against an absolute axis-aligned box associated with [obj]. */
    fun <T> hitBox(
        boxStart: Point,
        boxEnd: Point,
        obj: T,
    ): Hit<T>? {
        val fraction = segmentBoxIntersection(origin, vector, boxStart, boxEnd) ?: return null
        return Hit(distance * fraction, origin.add(vector.mul(fraction)), obj)
    }

    /** Casts against [entity] at its current server position. */
    fun <E : Entity> hitEntity(entity: E): Hit<E>? = cast(entity, entity.position)

    /** The closest entity the ray hits, or null if it hits none. */
    fun <E : Entity> firstEntity(entities: Collection<E>): Hit<E>? {
        var best: Hit<E>? = null
        for (entity in entities) {
            val hit = hitEntity(entity) ?: continue
            if (best == null || hit.t < best.t) best = hit
        }
        return best
    }

    /**
     * The closest block collision shape the ray hits, or null if it hits none. Hits that [passThrough]
     * accepts are skipped; it receives each hit block's cell, nearest cell first.
     */
    fun firstBlock(
        instance: Instance,
        passThrough: ((BlockVec, Hit<Block>) -> Boolean)? = null,
    ): Hit<Block>? {
        fun stoppingHitAt(pos: Point): Hit<Block>? {
            val hit = firstBlockAt(instance, pos) ?: return null
            return if (passThrough?.invoke(pos.asBlockVec(), hit) == true) null else hit
        }

        if (distance == 0.0) {
            return stoppingHitAt(Vec(origin.blockX().toDouble(), origin.blockY().toDouble(), origin.blockZ().toDouble()))
        }

        return firstAlongCells(::stoppingHitAt)
    }

    /**
     * The first non-null [find] result over the block cells along the ray, nearest first. Visits no
     * cells for a non-finite ray.
     *
     * BlockIterator only stops once it lands on the end cell, which it can step past and never revisit:
     * a near-zero negative component (eye.direction() at yaw 180 gives x of about -1.2e-16) from an
     * origin exactly on a block boundary leaves the end cell on the first step. The step cap bounds
     * the walk: a segment crosses at most |x| + |y| + |z| + 3 boundaries and the iterator yields at
     * most 7 cells per 3 crossings. Cells past the segment are harmless because hits clip to [vector].
     */
    internal inline fun <T : Any> firstAlongCells(find: (Point) -> T?): T? {
        if (!distance.isFinite() || !origin.x().isFinite() || !origin.y().isFinite() || !origin.z().isFinite()) {
            return null
        }
        val maxSteps = (3 * (abs(vector.x()) + abs(vector.y()) + abs(vector.z()))).toInt() + 12
        val iterator = BlockIterator(origin.asVec(), direction, 0.0, distance)
        var steps = 0
        while (iterator.hasNext() && steps++ < maxSteps) find(iterator.next())?.let { return it }
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
