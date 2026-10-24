/*
 * Coordinate system for Nodes, follow game chunks
 */

package net.aechronis.nodes.objects

const val CHUNK_SIZE: Int = 16

fun toChunk(v: Int): Int = v.floorDiv(CHUNK_SIZE)

data class Coord(val x: Int, val z: Int) {
    // Mix both coordinates before folding to 32 bits. Folding the packed pair directly
    // gives x xor z, clustering the world's chunk index into a few thousand hash buckets.
    // MurmurHash3's 64-bit finalizer also disperses diagonal and negative coordinates.
    override fun hashCode(): Int {
        var hash = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
        hash = (hash xor (hash ushr 33)) * -49064778989728563L
        hash = (hash xor (hash ushr 33)) * -4265267296055464877L
        hash = hash xor (hash ushr 33)
        return (hash xor (hash ushr 32)).toInt()
    }

    companion object {
        fun fromBlockCoords(x: Int, z: Int): Coord = Coord(toChunk(x), toChunk(z))
    }
}
