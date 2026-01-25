/*
 * Coordinate system for Nodes, follow game chunks
 */

package luna.nodes.objects

const val CHUNK_SIZE: Int = 16

fun toChunk(v: Int): Int = v.floorDiv(CHUNK_SIZE)

data class Coord(val x: Int, val z: Int) {
    companion object {
        fun fromBlockCoords(x: Int, z: Int): Coord = Coord(toChunk(x), toChunk(z))
    }
}
