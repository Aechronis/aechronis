package net.aechronis.nodes.objects

internal const val MAX_MINIMAP_RECTANGLE_SIZE = 16

internal data class MinimapTerritoryCell(
    val territoryId: TerritoryId,
    val codepoint: Int,
    val home: Boolean,
    val borders: Int,
) {
    fun sameFill(other: MinimapTerritoryCell): Boolean = territoryId == other.territoryId && codepoint == other.codepoint && home == other.home
}

internal data class MinimapTerritoryRectangle(
    val x: Int,
    val z: Int,
    val width: Int,
    val height: Int,
    val cell: MinimapTerritoryCell,
)

/** Exact cover: never merge through a boundary or turn a partial edge into a full outline. */
internal fun mergeMinimapTerritories(cells: Array<Array<MinimapTerritoryCell?>>): List<MinimapTerritoryRectangle> {
    if (cells.isEmpty()) return emptyList()
    val rows = cells.size
    val columns = cells[0].size
    require(cells.all { it.size == columns })
    val used = Array(rows) { BooleanArray(columns) }
    val rectangles = mutableListOf<MinimapTerritoryRectangle>()
    for (z in 0 until rows) {
        for (x in 0 until columns) {
            val first = cells[z][x] ?: continue
            if (used[z][x]) continue
            var width = 1
            var borders = first.borders
            while (width < MAX_MINIMAP_RECTANGLE_SIZE && x + width < columns && borders and 2 == 0) {
                val next = cells[z][x + width] ?: break
                if (used[z][x + width] ||
                    !first.sameFill(next) ||
                    next.borders and 1 != 0 ||
                    next.borders and 12 != borders and 12
                ) {
                    break
                }
                borders = (borders and 2.inv()) or (next.borders and 2)
                width++
            }
            var height = 1
            while (height < MAX_MINIMAP_RECTANGLE_SIZE && z + height < rows && borders and 8 == 0) {
                val south = cells[z + height][x]?.borders?.and(8) ?: break
                val compatible = (0 until width).all { dx ->
                    val next = cells[z + height][x + dx]
                    val expected = south or (if (dx == 0) borders and 1 else 0) or
                        (if (dx == width - 1) borders and 2 else 0)
                    next != null && !used[z + height][x + dx] && first.sameFill(next) && next.borders == expected
                }
                if (!compatible) break
                borders = (borders and 8.inv()) or south
                height++
            }
            for (dz in 0 until height) {
                for (dx in 0 until width) used[z + dz][x + dx] = true
            }
            rectangles.add(MinimapTerritoryRectangle(x, z, width, height, first.copy(borders = borders)))
        }
    }
    return rectangles
}

/** Conservative circle test over every camera position within the cached center chunk. */
internal fun minimapChunkMayBeVisible(chunkX: Int, chunkZ: Int, centerX: Int, centerZ: Int, scale: Int): Boolean {
    val centerChunkX = Math.floorDiv(centerX, 16)
    val centerChunkZ = Math.floorDiv(centerZ, 16)
    val dx = (kotlin.math.abs(chunkX.toLong() - centerChunkX) - 1).coerceAtLeast(0) * 16
    val dz = (kotlin.math.abs(chunkZ.toLong() - centerChunkZ) - 1).coerceAtLeast(0) * 16
    val radius = 63L * scale
    return dx * dx + dz * dz <= radius * radius
}
