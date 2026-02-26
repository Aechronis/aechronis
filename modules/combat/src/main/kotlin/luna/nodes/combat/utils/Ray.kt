// Minestom raycaster PR (condensed and hopefully usable) - see Minestom/Minestom #2967

// Usage:
// Construct a ray using Ray(Point origin, Vec ray)
// Call cast() on the ray to cast against a specified bounding box
// Call entities() or firstEntity() on the ray to cast against entities
// Call findBlocks() or blockQueue() on the ray to cast against blocks

// https://gist.github.com/thecodertommy/e74fe3b192948096c3363ad3a298762e
// https://github.com/Minestom/Minestom/pull/2967

package luna.nodes.combat.utils

import net.minestom.server.collision.BoundingBox
import net.minestom.server.collision.Shape
import net.minestom.server.collision.ShapeImpl
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.instance.block.Block
import net.minestom.server.utils.block.BlockIterator
import net.minestom.server.utils.validate.Check
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * A ray that can check for collisions along it.
 *
 * You should construct a Ray using [Ray].
 * @param origin the ray's origin
 * @param direction the ray's normalized direction
 * @param distance the maximum distance the ray will check
 * @param inverse the cached inverse of the ray
 */
data class Ray(
    val origin: Point,
    val direction: Vec,
    val distance: Double,
    val inverse: Vec,
) {
    /**
     * Constructs a ray.
     * @param origin the origin point
     * @param vector the ray's path, which can have any nonzero length
     */
    constructor(origin: Point, vector: Vec) : this(
        origin,
        vector.normalize().also {
            Check.argCondition(vector.isZero, "Ray may not have zero length")
        },
        vector.length(),
        Vec.ONE.div(vector.normalize()),
    )

    /**
     * An intersection found between a [Ray] and object of type [T].
     * @param T the type of object collided with
     * @param t the distance along the ray that the intersection was found
     * @param point the point of intersection
     * @param normal the normal of the intersected surface
     * @param exitT the distance along the ray that the ray exits the object
     * @param exitPoint the point from which the ray exits the object
     * @param exitNormal the normal of the surface through which the ray exits
     * @param obj the object collided with
     */
    data class Intersection<T>(
        val t: Double,
        val point: Point,
        val normal: Vec,
        val exitT: Double,
        val exitPoint: Point,
        val exitNormal: Vec,
        val obj: T,
    ) : Comparable<Intersection<*>> {
        /**
         * Compares this intersection's t value with that of another one. If they are equal, compares their exitT values.
         * @param other Any other intersection
         * @return a negative integer, zero, or a positive integer as this object is less than, equal to, or greater than the specified object.
         */
        override fun compareTo(other: Intersection<*>): Int =
            if (t != other.t) {
                (t - other.t).sign.toInt()
            } else {
                (exitT - other.exitT).sign.toInt()
            }

        /**
         * Creates a copy of this intersection with the specified hit object
         * @param newObject the new object
         * @return a new intersection
         * @param R the type of the hit object
         */
        fun <R> withObject(newObject: R): Intersection<R> = Intersection(t, point, normal, exitT, exitPoint, exitNormal, newObject)

        /**
         * Returns whether an intersection overlaps with another; if one's [Intersection.exitT] is less than or equal to the other's [Intersection.t].
         *
         * Use this to validate before using [Intersection.merge].
         * @param other the other intersection
         * @return whether the intersections overlap
         */
        fun overlaps(other: Intersection<*>): Boolean = !(other.exitT < t || exitT < other.t)

        /**
         * Merges two intersections by making one out of the lowest t and highest exitT from the intersections.
         * @param other the other intersection
         * @return a potentially larger intersection with the same [obj] as this
         */
        fun merge(other: Intersection<*>): Intersection<T> {
            val startsFirst = t < other.t
            val endsLast = exitT >= other.exitT
            return Intersection(
                if (startsFirst) t else other.t,
                if (startsFirst) point else other.point,
                if (startsFirst) normal else other.normal,
                if (endsLast) exitT else other.exitT,
                if (endsLast) exitPoint else other.exitPoint,
                if (endsLast) exitNormal else other.exitNormal,
                obj,
            )
        }
    }

    /**
     * Check if this ray hits some shape.
     * @param shape the shape to check against
     * @param offset an offset to shift the shape by, e.g. for block hitboxes
     * @return an [Intersection] if one is found between this ray and the shape, and null otherwise
     * @param S any Shape
     */
    fun <S : Shape> cast(
        shape: S,
        offset: Point,
    ): Intersection<S>? {
        val bMin =
            shape
                .relativeStart()
                .asVec()
                .sub(origin)
                .add(offset)
        val bMax =
            shape
                .relativeEnd()
                .asVec()
                .sub(origin)
                .add(offset)
        val v1 = bMin.mul(inverse)
        val v2 = bMax.mul(inverse)

        var tN = min(v1.x(), v2.x())
        var tF = max(v1.x(), v2.x())
        tN = max(tN, min(v1.y(), v2.y()))
        tF = min(tF, max(v1.y(), v2.y()))
        tN = max(tN, min(v1.z(), v2.z()))
        tF = min(tF, max(v1.z(), v2.z()))

        return if (tF >= tN && tF >= 0 && tN <= distance) {
            Intersection(
                tN,
                origin.add(direction.mul(tN)),
                Vec(
                    -(if (v1.x() == tN) 1.0 else 0.0) + (if (v2.x() == tN) 1.0 else 0.0),
                    -(if (v1.y() == tN) 1.0 else 0.0) + (if (v2.y() == tN) 1.0 else 0.0),
                    -(if (v1.z() == tN) 1.0 else 0.0) + (if (v2.z() == tN) 1.0 else 0.0),
                ),
                tF,
                origin.add(direction.mul(tF)),
                Vec(
                    -(if (v1.x() == tF) 1.0 else 0.0) + (if (v2.x() == tF) 1.0 else 0.0),
                    -(if (v1.y() == tF) 1.0 else 0.0) + (if (v2.y() == tF) 1.0 else 0.0),
                    -(if (v1.z() == tF) 1.0 else 0.0) + (if (v2.z() == tF) 1.0 else 0.0),
                ),
                shape,
            )
        } else {
            null
        }
    }

    /**
     * Check if this ray hits some shape.
     *
     * If you're checking an [Entity], use [Ray.cast] with its position.
     * @param shape the shape to check against
     * @return an [Intersection] if one is found between this ray and the shape, and null otherwise
     * @param S any Shape - for example, a [BoundingBox]
     */
    fun <S : Shape> cast(shape: S): Intersection<S>? = cast(shape, Vec.ZERO)

    /**
     * Get an **unordered** list of collisions with shapes.
     *
     * If you need to know which collisions happened first, use [castSorted] or [minOrNull].
     * @param shapes the shapes to check against
     * @return a list of results, possibly empty
     * @param S any Shape - for example, an [Entity] or [BoundingBox]
     */
    fun <S : Shape> cast(shapes: Collection<S>): List<Intersection<S>> {
        val result = ArrayList<Intersection<S>>(shapes.size)
        for (e in shapes) {
            val r = cast(e)
            if (r != null) result.add(r)
        }
        return result
    }

    /**
     * Get an ordered list of collisions with shapes, starting with the closest to the ray origin.
     * @param shapes the shapes to check against
     * @return a list of results, possibly empty
     * @param S any Shape - for example, a [BoundingBox]
     */
    fun <S : Shape> castSorted(shapes: Collection<S>): List<Intersection<S>> {
        val result = ArrayList<Intersection<S>>(shapes.size)
        for (e in shapes) {
            val r = cast(e)
            if (r != null) result.add(r)
        }
        result.sort()
        return result
    }

    /**
     * Get the closest collision to the ray's origin.
     * @param shapes the shapes to check against
     * @return the closest result or null if there is none
     * @param S any Shape - for example, a [BoundingBox]
     */
    fun <S : Shape> findFirst(shapes: Collection<S>): Intersection<S>? {
        var best: Intersection<S>? = null
        var bestT = distance
        for (e in shapes) {
            val r = cast(e)
            if (r != null && r.t <= bestT) {
                best = r
                bestT = r.t
            }
        }
        return best
    }

    /**
     * Get an **unordered** list of collisions with entities.
     *
     * If you need to know which collisions happened first, use [entitiesSorted] or [minOrNull].
     * @param entities the entities to check against
     * @return a list of results, possibly empty
     * @param E any Entity - if you're using [net.minestom.server.instance.EntityTracker], you might use [net.minestom.server.entity.Player]
     */
    fun <E : Entity> entities(entities: Collection<E>): List<Intersection<E>> {
        val result = ArrayList<Intersection<E>>(entities.size)
        for (e in entities) {
            val r = cast(e, e.position)
            if (r != null) result.add(r)
        }
        return result
    }

    /**
     * Get an ordered list of collisions with entities, starting with the closest to the ray origin.
     * @param entities the entities to check against
     * @return a list of results, possibly empty
     * @param E any Entity - if you're using [net.minestom.server.instance.EntityTracker], you might use [net.minestom.server.entity.Player]
     */
    fun <E : Entity> entitiesSorted(entities: Collection<E>): List<Intersection<E>> {
        val result = ArrayList<Intersection<E>>(entities.size)
        for (e in entities) {
            val r = cast(e, e.position)
            if (r != null) result.add(r)
        }
        result.sort()
        return result
    }

    /**
     * Get the closest entity collision to the ray's origin.
     * @param entities the entities to check against
     * @return the closest result or null if there is none
     * @param E any Entity - if you're using [net.minestom.server.instance.EntityTracker], you might use [net.minestom.server.entity.Player]
     */
    fun <E : Entity> firstEntity(entities: Collection<E>): Intersection<E>? {
        var best: Intersection<E>? = null
        var bestT = distance
        for (e in entities) {
            val r = cast(e, e.position)
            if (r != null && r.t <= bestT) {
                best = r
                bestT = r.t
            }
        }
        return best
    }

    /**
     * Gets a [BlockIterator] along this ray.
     * @return a [BlockIterator]
     */
    fun blockIterator(): BlockIterator = BlockIterator(origin.asVec(), direction, 0.0, distance)

    /**
     * Gets a [BlockFinder] along this ray.
     *
     * This is useful if you need only the first hit point, for instance, as it does not perform merging.
     * @param blockGetter the provider for blocks, such as an [net.minestom.server.instance.Instance] or [net.minestom.server.instance.Chunk]
     * @return a [BlockFinder]
     */
    fun findBlocks(blockGetter: Block.Getter): BlockFinder =
        BlockFinder(this, blockIterator(), blockGetter, BlockFinder.SOLID_BLOCK_HITBOXES)

    /**
     * Gets a [BlockFinder] along this ray.
     *
     * This is useful if you need only the first hit point, for instance, as it does not perform merging.
     * @param blockGetter the provider for blocks, such as an [net.minestom.server.instance.Instance] or [net.minestom.server.instance.Chunk]
     * @param hitboxGetter a function that gets bounding boxes from a block
     *
     * [BlockFinder] provides some options, and [BlockFinder.SOLID_BLOCK_HITBOXES] is the default.
     * @return a [BlockFinder]
     */
    fun findBlocks(
        blockGetter: Block.Getter,
        hitboxGetter: (Block) -> Collection<BoundingBox>,
    ): BlockFinder = BlockFinder(this, blockIterator(), blockGetter, hitboxGetter)

    /**
     * Gets a [BlockQueue] along this ray.
     *
     * These can perform merging. They are useful if you need exit points from blocks.
     * @param blockGetter the provider for blocks, such as an [net.minestom.server.instance.Instance] or [net.minestom.server.instance.Chunk]
     * @return a [BlockQueue]
     */
    fun blockQueue(blockGetter: Block.Getter): BlockQueue = BlockQueue(findBlocks(blockGetter))

    /**
     * Gets a [BlockQueue] along this ray.
     *
     * These can perform merging. They are useful if you need exit points from blocks.
     * @param blockGetter the provider for blocks, such as an [net.minestom.server.instance.Instance] or [net.minestom.server.instance.Chunk]
     * @param hitboxGetter a function that gets bounding boxes from a block
     *
     * [BlockFinder] provides some options, and [BlockFinder.SOLID_BLOCK_HITBOXES] is the default.
     * @return a [BlockQueue]
     */
    fun blockQueue(
        blockGetter: Block.Getter,
        hitboxGetter: (Block) -> Collection<BoundingBox>,
    ): BlockQueue = BlockQueue(findBlocks(blockGetter, hitboxGetter))

    /**
     * Gets the end point of this ray with some data that may or may not be useful.
     * @return the end point as a result
     */
    fun endPoint(): Intersection<Ray> =
        Intersection(
            distance,
            origin.add(direction.mul(distance)),
            direction.neg(),
            distance,
            origin.add(direction.mul(distance)),
            direction,
            this,
        )

    data class BlockFinder(
        val ray: Ray,
        val blockIterator: BlockIterator,
        val blockGetter: Block.Getter,
        val hitboxGetter: (Block) -> Collection<BoundingBox>,
    ) : Iterator<Collection<Intersection<Block>>> {
        companion object {
            /**
             * A hitbox getter that finds a block's collision hitboxes.
             */
            val SOLID_BLOCK_HITBOXES: (Block) -> Collection<BoundingBox> =
                { block -> (block.registry()?.collisionShape() as? ShapeImpl)?.boundingBoxes() ?: emptyList() }

            /**
             * A 1x1x1 block hitbox.
             */
            private val CUBE = listOf(BoundingBox(Vec.ZERO, Vec.ONE))

            /**
             * A hitbox getter that returns a cube if the block has any solid collision.
             */
            val SOLID_CUBE_HITBOXES: (Block) -> Collection<BoundingBox> =
                { block -> if (block.isSolid) CUBE else emptyList() }

            /**
             * A hitbox getter that returns a cube if the block is not air.
             */
            val CUBE_HITBOXES: (Block) -> Collection<BoundingBox> =
                { block -> if (!block.isAir) CUBE else emptyList() }
        }

        override fun hasNext(): Boolean = blockIterator.hasNext()

        override fun next(): List<Intersection<Block>> {
            val results = ArrayList<Intersection<Block>>()
            if (blockIterator.hasNext()) {
                val p = blockIterator.next()

                // check if chunk is loaded
                if (blockGetter is net.minestom.server.instance.Instance) {
                    if (!blockGetter.isChunkLoaded(p.chunkX(), p.chunkZ())) {
                        return emptyList()
                    }
                }

                val b = blockGetter.getBlock(p)
                val hitboxes = hitboxGetter(b)
                if (hitboxes.isNotEmpty()) {
                    for (h in hitboxes) {
                        val r = ray.cast(h, p.asVec())
                        if (r != null) results.add(r.withObject(b))
                    }
                    if (results.isNotEmpty()) {
                        results.sort()
                        return results
                    }
                }
            }
            return emptyList()
        }

        /**
         * Return the next closest intersection.
         * Keep in mind that this discards all other hits within the found block.
         * @return the next closest intersection, or null if there are none
         */
        fun nextClosest(): Intersection<Block>? {
            while (blockIterator.hasNext()) {
                val results = next()
                if (results.isNotEmpty()) return results.minOrNull()
            }
            return null
        }
    }

    class BlockQueue(
        private val refiller: Iterator<Collection<Intersection<Block>>>,
    ) : ArrayDeque<Intersection<Block>>() {
        companion object {
            /**
             * A predicate that passes if two block intersections have the same block type, ignoring state.
             */
            val SAME_BLOCK_TYPE: (Intersection<Block>, Intersection<Block>) -> Boolean =
                { i1, i2 -> i1.obj.compare(i2.obj) }
        }

        /**
         * Refill this queue with zero or more results.
         * @return number of entries added
         */
        fun refill(): Int {
            if (!refiller.hasNext()) return 0
            val next = refiller.next()
            addAll(next)
            return next.size
        }

        /**
         * Keep refilling until something is added or the refiller cannot add anything more.
         * @return number of entries added, zero if the refiller does not have a next element
         */
        fun refillSome(): Int {
            while (refiller.hasNext()) {
                val result = refill()
                if (result > 0) return result
            }
            return 0
        }

        /**
         * Keep refilling until the refiller does not have a next element.
         * @return number of entries added
         */
        fun refillAll(): Int {
            var added = 0
            while (refiller.hasNext()) {
                added += refill()
            }
            return added
        }

        /**
         * If the first and second elements exist and [Intersection.overlaps] can merge,
         * merge them, otherwise do nothing
         * @param predicate a predicate for merging
         * @return whether elements were merged
         */
        fun merge(predicate: (Intersection<Block>, Intersection<Block>) -> Boolean): Boolean {
            if (isEmpty()) return false
            val first = poll()
            val next = peek()
            if (next == null || !first.overlaps(next) || !predicate(first, next)) {
                addFirst(first)
                return false
            }
            remove()
            addFirst(first.merge(next))
            return true
        }

        /**
         * If the first and second elements exist and [Intersection.overlaps] can merge,
         * merge them, otherwise do nothing
         * @return whether elements were merged
         */
        fun merge(): Boolean = merge { _, _ -> true }

        /**
         * [merge] for as long as possible.
         * @param predicate a predicate for merging
         * @return number of times merged
         */
        fun mergeAll(predicate: (Intersection<Block>, Intersection<Block>) -> Boolean): Int {
            var merged = 0
            while (merge(predicate)) merged++
            return merged
        }

        /**
         * [merge] for as long as possible.
         * @return number of times merged
         */
        fun mergeAll(): Int {
            var merged = 0
            while (merge()) merged++
            return merged
        }
    }
}
