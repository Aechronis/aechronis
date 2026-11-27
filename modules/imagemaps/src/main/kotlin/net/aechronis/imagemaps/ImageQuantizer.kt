package net.aechronis.imagemaps

import java.awt.Image
import java.awt.image.BufferedImage

/** An image converted to vanilla map colour bytes, split row-major into 128x128 tiles. */
class Tiles(
    val cols: Int,
    val rows: Int,
    val tiles: List<ByteArray>,
)

object ImageQuantizer {
    const val TILE = 128
    const val MAX_COLS = 4
    const val MAX_ROWS = 4

    // Vanilla map base colours 1..61 as 0xRRGGBB (index 0 is transparent). Colour byte = base * 4 + shade.
    private val BASE_COLORS =
        intArrayOf(
            0x000000,
            0x7FB238,
            0xF7E9A3,
            0xC7C7C7,
            0xFF0000,
            0xA0A0FF,
            0xA7A7A7,
            0x007C00,
            0xFFFFFF,
            0xA4A8B8,
            0x976D4D,
            0x707070,
            0x4040FF,
            0x8F7748,
            0xFFFCF5,
            0xD87F33,
            0xB24CD8,
            0x6699D8,
            0xE5E533,
            0x7FCC19,
            0xF27FA5,
            0x4C4C4C,
            0x999999,
            0x4C7F99,
            0x7F3FB2,
            0x334CB2,
            0x664C33,
            0x667F33,
            0x993333,
            0x191919,
            0xFAEE4D,
            0x5CDBD5,
            0x4A80FF,
            0x00D93A,
            0x815631,
            0x700200,
            0xD1B1A1,
            0x9F5224,
            0x95576C,
            0x706C8A,
            0xBA8524,
            0x677535,
            0xA04D4E,
            0x392923,
            0x876B62,
            0x575C5C,
            0x7A4958,
            0x4C3E5C,
            0x4C3223,
            0x4C522A,
            0x8E3C2E,
            0x251610,
            0xBD3031,
            0x943F61,
            0x5C191D,
            0x167E86,
            0x3A8E8C,
            0x562C3E,
            0x14B485,
            0x646464,
            0xD8AF93,
            0x7FA796,
        )
    private val SHADES = intArrayOf(180, 220, 255, 135)

    // palette[colour byte] = 0xRRGGBB; entries 0..3 are the transparent base and are never matched.
    private val palette =
        IntArray(BASE_COLORS.size * 4) { index ->
            val base = BASE_COLORS[index / 4]
            val shade = SHADES[index % 4]

            fun channel(shift: Int) = ((base shr shift) and 0xFF) * shade / 255
            (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

    /** Scales to [cols] tiles wide (rows follow the aspect ratio, capped at [MAX_ROWS]) and quantizes. */
    fun quantize(
        source: BufferedImage,
        cols: Int,
    ): Tiles {
        val tileCols = cols.coerceIn(1, MAX_COLS)
        val width = tileCols * TILE
        val rows = Math.round(width.toDouble() * source.height / source.width / TILE).toInt().coerceIn(1, MAX_ROWS)
        val height = rows * TILE
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        graphics.drawImage(source.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING), 0, 0, null)
        graphics.dispose()
        val argb = scaled.getRGB(0, 0, width, height, null, 0, width)
        val colors = ByteArray(width * height) { nearest(argb[it]) }
        val tiles =
            List(rows * tileCols) { tile ->
                val originX = tile % tileCols * TILE
                val originY = tile / tileCols * TILE
                ByteArray(TILE * TILE) { colors[(originY + it / TILE) * width + originX + it % TILE] }
            }
        return Tiles(tileCols, rows, tiles)
    }

    private fun nearest(argb: Int): Byte {
        if ((argb ushr 24) < 128) return 0
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        var best = 4
        var bestDistance = Int.MAX_VALUE
        for (index in 4 until palette.size) {
            val color = palette[index]
            val pr = (color shr 16) and 0xFF
            val dr = r - pr
            val dg = g - ((color shr 8) and 0xFF)
            val db = b - (color and 0xFF)
            val mean = (r + pr) / 2
            // Redmean weighting tracks perceived colour difference better than plain RGB distance.
            val distance = ((512 + mean) * dr * dr shr 8) + 4 * dg * dg + ((767 - mean) * db * db shr 8)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best.toByte()
    }
}
