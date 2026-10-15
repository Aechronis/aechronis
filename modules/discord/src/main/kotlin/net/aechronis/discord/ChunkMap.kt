package net.aechronis.discord

import com.sk89q.worldedit.extent.clipboard.Clipboard
import net.minestom.server.instance.block.Block
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** A top-down render of a schematic with the world's 16x16 chunk grid over it, labeled A1, B1, ... */
internal class ChunkMap(
    val minCx: Int,
    val minCz: Int,
    val cols: Int,
    val rows: Int,
    private val scale: Int,
    private val base: BufferedImage,
    /** Chunks holding at least one non-air block; only these can be pasted. */
    val filled: Set<Long>,
) {
    fun label(packed: Long): String = "${'A' + (cx(packed) - minCx)}${cz(packed) - minCz + 1}"

    fun at(
        col: Int,
        row: Int,
    ): Long = pack(minCx + col, minCz + row)

    /** The render with selected chunks tinted green and already-paid chunks blue. */
    fun png(
        selected: Set<Long>,
        paid: Set<Long>,
        blocked: Set<Long> = emptySet(),
    ): ByteArray {
        val image = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.drawImage(base, 0, 0, null)
            val size = 16 * scale
            val font = Font(Font.SANS_SERIF, Font.BOLD, (size / 3).coerceIn(10, 16))
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    val packed = at(col, row)
                    val x = col * size
                    val y = row * size
                    when {
                        packed !in filled -> {
                            g.color = Color(0, 0, 0, 130)
                            g.fillRect(x, y, size, size)
                        }
                        packed in blocked -> {
                            g.color = Color(230, 50, 50, 110)
                            g.fillRect(x, y, size, size)
                        }
                        packed in paid -> {
                            g.color = Color(60, 120, 255, 80)
                            g.fillRect(x, y, size, size)
                        }
                        packed in selected -> {
                            g.color = Color(60, 220, 100, 90)
                            g.fillRect(x, y, size, size)
                        }
                    }
                    g.color = Color(255, 255, 255, 70)
                    g.drawRect(x, y, size, size)
                    if (packed in filled) label(g, font, label(packed), x + 4, y + 4 + g.fontMetrics.ascent)
                }
            }
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    // Text needs fonts; a minimal server image may have none, and the buttons carry the labels anyway.
    private fun label(
        g: java.awt.Graphics2D,
        font: Font,
        text: String,
        x: Int,
        y: Int,
    ) {
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = font
            g.color = Color(0, 0, 0, 200)
            g.drawString(text, x + 1, y + 1)
            g.color = Color.WHITE
            g.drawString(text, x, y)
        } catch (_: Throwable) {
        }
    }

    companion object {
        init {
            System.setProperty("java.awt.headless", "true")
        }

        fun pack(
            cx: Int,
            cz: Int,
        ): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)

        fun cx(packed: Long): Int = (packed shr 32).toInt()

        fun cz(packed: Long): Int = packed.toInt()

        // Vanilla map base colors by id; 0 means the block has no map color.
        private val PALETTE =
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

        /** Renders the schematic from above, aligned to the world's chunk grid for an origin placed at (x, z). */
        fun render(
            clipboard: Clipboard,
            x: Int,
            z: Int,
            filled: Set<Long>,
        ): ChunkMap {
            val origin = clipboard.origin
            val min = clipboard.region.minimumPoint
            val max = clipboard.region.maximumPoint
            val dx = x - origin.x()
            val dz = z - origin.z()
            val minCx = (min.x() + dx) shr 4
            val minCz = (min.z() + dz) shr 4
            val cols = ((max.x() + dx) shr 4) - minCx + 1
            val rows = ((max.z() + dz) shr 4) - minCz + 1
            val width = cols * 16
            val length = rows * 16
            val scale = (960 / maxOf(width, length)).coerceIn(2, 8)
            val topY = Array(width) { IntArray(length) { Int.MIN_VALUE } }
            val color = Array(width) { IntArray(length) { -1 } }
            val colorCache = HashMap<String, Int>()
            for (pos in clipboard.region) {
                val type = clipboard.getBlock(pos).blockType
                if (type.material.isAir) continue
                val rgb =
                    colorCache.getOrPut(type.id()) {
                        val id = Block.fromKey(type.id())?.registry()?.mapColorId() ?: 0
                        if (id in 1 until PALETTE.size) PALETTE[id] else -1
                    }
                val bx = pos.x() + dx - (minCx shl 4)
                val bz = pos.z() + dz - (minCz shl 4)
                // Blocks with no map color (glass, air-like) only show when nothing solid is above ground there.
                if (rgb >= 0 && pos.y() > topY[bx][bz]) {
                    topY[bx][bz] = pos.y()
                    color[bx][bz] = rgb
                } else if (rgb < 0 && color[bx][bz] == -1 && pos.y() > topY[bx][bz]) {
                    topY[bx][bz] = pos.y()
                    color[bx][bz] = 0x909090
                }
            }
            val image = BufferedImage(width * scale, length * scale, BufferedImage.TYPE_INT_RGB)
            val pixels = IntArray(image.width * image.height) { 0x1E1F22 }
            for (bx in 0 until width) {
                for (bz in 0 until length) {
                    val rgb = color[bx][bz]
                    if (rgb < 0) continue
                    val north = if (bz > 0) topY[bx][bz - 1] else topY[bx][bz]
                    val shade =
                        when {
                            topY[bx][bz] > north -> 1.0
                            topY[bx][bz] == north -> 0.86
                            else -> 0.71
                        }
                    val shaded =
                        (((rgb shr 16 and 0xFF) * shade).toInt() shl 16) or
                            (((rgb shr 8 and 0xFF) * shade).toInt() shl 8) or
                            ((rgb and 0xFF) * shade).toInt()
                    for (py in 0 until scale) {
                        val rowStart = (bz * scale + py) * image.width + bx * scale
                        for (px in 0 until scale) pixels[rowStart + px] = shaded
                    }
                }
            }
            image.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
            return ChunkMap(minCx, minCz, cols, rows, scale, image, filled)
        }
    }
}
