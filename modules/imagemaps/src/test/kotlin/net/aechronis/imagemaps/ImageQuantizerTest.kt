package net.aechronis.imagemaps

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageQuantizerTest {
    @Test
    fun `splits a 256x128 image into two tiles with exact colour bytes`() {
        val image = BufferedImage(256, 128, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                image.setRGB(x, y, 0xFFFF0000.toInt()) // opaque red on the left
                image.setRGB(x + 128, y, 0x00000000) // transparent on the right
            }
        }

        val result = ImageQuantizer.quantize(image, 2)

        assertEquals(2, result.cols)
        assertEquals(1, result.rows)
        assertEquals(2, result.tiles.size)
        // FIRE base colour (4) at full brightness (shade 2) is byte 4 * 4 + 2.
        assertTrue(result.tiles[0].all { it == 18.toByte() })
        assertTrue(result.tiles[1].all { it == 0.toByte() })
    }

    @Test
    fun `tall images get more rows and tile order is row-major`() {
        val image = BufferedImage(128, 256, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 256) {
            for (x in 0 until 128) image.setRGB(x, y, if (y < 128) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
        }

        val result = ImageQuantizer.quantize(image, 1)

        assertEquals(2, result.rows)
        // SNOW (8) full brightness = 34; pure black's nearest is the darkest shade (3) of base 51, so 51 * 4 + 3.
        assertEquals(setOf(34), result.tiles[0].map { it.toInt() and 0xFF }.toSet())
        assertEquals(setOf(51 * 4 + 3), result.tiles[1].map { it.toInt() and 0xFF }.toSet())
    }
}
