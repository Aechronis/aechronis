// Construct a ray with Ray(origin, vector); the vector's length is the max distance checked.
// Call firstEntity() to hit entities, or firstBlock() to hit solid blocks.

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

/**
 * A ray cast from [origin] along [vector], checking for collisions up to [vector]'s length.
 */
class Ray(
    private val origin: Point,
    vector: Vec,
) {
    val direction: Vec = vector.normalize()
    val distance: Double = vector.length()
    private val inverse: Vec = Vec.ONE.div(direction)

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
        val v1 =
            shape
                .relativeStart()
                .asVec()
                .sub(origin)
                .add(offset)
                .mul(inverse)
        val v2 =
            shape
                .relativeEnd()
                .asVec()
                .sub(origin)
                .add(offset)
                .mul(inverse)

        var tNear = min(v1.x(), v2.x())
        var tFar = max(v1.x(), v2.x())
        tNear = max(tNear, min(v1.y(), v2.y()))
        tFar = min(tFar, max(v1.y(), v2.y()))
        tNear = max(tNear, min(v1.z(), v2.z()))
        tFar = min(tFar, max(v1.z(), v2.z()))

        if (tFar < tNear || tFar < 0 || tNear > distance) return null
        return Hit(tNear, origin.add(direction.mul(tNear)), shape)
    }

    /** The closest entity the ray hits, or null if it hits none. */
    fun <E : Entity> firstEntity(entities: Collection<E>): Hit<E>? {
        var best: Hit<E>? = null
        for (entity in entities) {
            val hit = cast(entity, entity.position) ?: continue
            if (best == null || hit.t < best!!.t) best = hit
        }
        return best
    }

    /** The closest solid block the ray hits, or null if it hits none. */
    fun firstBlock(instance: Instance): Hit<Block>? {
        val iterator = BlockIterator(origin.asVec(), direction, 0.0, distance)
        while (iterator.hasNext()) {
            val pos = iterator.next()
            if (!instance.isChunkLoaded(pos.chunkX(), pos.chunkZ())) continue

            val block = instance.getBlock(pos)
            val hitboxes = (block.registry()?.collisionShape() as? ShapeImpl)?.boundingBoxes() ?: continue

            var best: Hit<Block>? = null
            for (hitbox in hitboxes) {
                val hit = cast(hitbox, pos.asVec()) ?: continue
                if (best == null || hit.t < best!!.t) best = Hit(hit.t, hit.point, block)
            }
            if (best != null) return best
        }
        return null
    }
}
