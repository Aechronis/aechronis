/*
 * Coordinate system for Nodes, follow game chunks
 */

package net.aechronis.nodes.objects

const val CHUNK_SIZE: Int = 16

fun toChunk(v: Int): Int = v.floorDiv(CHUNK_SIZE)

data class Coord(val x: Int, val z: Int) {
    // The default data-class hash (31*x + z) collides constantly for grid coordinates (e.g.
    // (1,31) and (2,0) both hash to 62), which treeifies buckets in the ConcurrentHashMaps keyed
    // by Coord across this module and turns O(1) lookups into tree traversals under load. Packing
    // into a long and folding it (the same trick java.lang.Long.hashCode uses) spreads bits from
    // both x and z instead of letting them cancel out arithmetically.
    override fun hashCode(): Int {
        val packed = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
        return (packed xor (packed ushr 32)).toInt()
    }

    companion object {
        fun fromBlockCoords(x: Int, z: Int): Coord = Coord(toChunk(x), toChunk(z))
    }
}
