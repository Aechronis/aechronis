/*
 * Coordinate system for Nodes, follow game chunks
 */

package luna.nodes.objects

const val CHUNK_SIZE: Int = 16

fun toChunk(v: Int): Int = v.floorDiv(CHUNK_SIZE)

data class Coord(val x: Int, val z: Int) {
    companion object {
        fun fromBlockCoords(x: Int, z: Int): Coord = Coord(toChunk(x), toChunk(z))

        // return coord from string in format "x,z"
        // two numbers separated by comma (with no spaces)
        fun fromString(s: String): Coord? {
            val splitIndex = s.indexOf(",")
            if (splitIndex != -1 && splitIndex < s.length) {
                try {
                    val x = s.substring(0, splitIndex).toInt()
                    val z = s.substring(splitIndex + 1).toInt()
                    return Coord(x, z)
                } catch (e: NumberFormatException) {
                    System.err.println("Invalid Coord string: $s")
                }
            }

            return null
        }
    }
}
