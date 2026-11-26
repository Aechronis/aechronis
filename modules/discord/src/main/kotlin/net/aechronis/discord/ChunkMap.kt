package net.aechronis.discord

import com.sk89q.worldedit.extent.clipboard.Clipboard
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * A top-down render of the world around a paste site with the schematic drawn in place, like a web map,
 * and the world's 16x16 chunk grid over it. The schematic's chunks are labeled A1, B1, ...
 */
internal class ChunkMap(
    val minCx: Int,
    val minCz: Int,
    val cols: Int,
    val rows: Int,
    private val scale: Int,
    private val base: BufferedImage,
    /** Chunks holding at least one non-air block; only these can be pasted. */
    val filled: Set<Long>,
    /** Chunks of the buyer's own town in view; everything else is shaded so the town's land stands out. */
    val own: Set<Long> = emptySet(),
    /** Chunks of other towns in view. */
    val claimed: Set<Long> = emptySet(),
) {
    /** The plain render the web picker shows; encoded once since it never changes. */
    val web: ByteArray by lazy { png(emptySet(), emptySet()) }

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
                        packed !in filled && packed in own -> Unit
                        packed !in filled && packed in claimed -> {
                            g.color = Color(200, 40, 40, 90)
                            g.fillRect(x, y, size, size)
                        }
                        packed !in filled -> {
                            g.color = Color(0, 0, 0, 120)
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
                    g.color = if (packed in filled) Color(255, 255, 255, 200) else Color(255, 255, 255, 35)
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

        /** Chunks of world shown around the schematic on each side, so the player sees where it lands. */
        const val MARGIN = 3

        private const val LOAD_BATCH = 64

        /** The chunks inside minCx, minCz, cols, rows bounds, row-major. */
        fun area(bounds: IntArray): List<Long> {
            val (minCx, minCz, cols, rows) = bounds
            return (0 until rows).flatMap { row -> (0 until cols).map { col -> pack(minCx + col, minCz + row) } }
        }

        /** Bounds covering all four rotations at this origin, so turning the schematic never moves the view. */
        fun bounds(
            rotations: List<Footprint>,
            x: Int,
            z: Int,
        ): IntArray {
            val all = rotations.map { bounds(it, x, z) }
            val minCx = all.minOf { it[0] }
            val minCz = all.minOf { it[1] }
            val maxCx = all.maxOf { it[0] + it[2] - 1 }
            val maxCz = all.maxOf { it[1] + it[3] - 1 }
            return intArrayOf(minCx, minCz, maxCx - minCx + 1, maxCz - minCz + 1)
        }

        /** minCx, minCz, cols, rows of a render at this origin. */
        fun bounds(
            footprint: Footprint,
            x: Int,
            z: Int,
        ): IntArray {
            val minCx = ((x + footprint.minX) shr 4) - MARGIN
            val minCz = ((z + footprint.minZ) shr 4) - MARGIN
            val cols = ((x + footprint.minX + footprint.width - 1) shr 4) + MARGIN - minCx + 1
            val rows = ((z + footprint.minZ + footprint.length - 1) shr 4) + MARGIN - minCz + 1
            return intArrayOf(minCx, minCz, cols, rows)
        }

        /** One pass over the schematic: rejects ores and empty schematics, and keeps each column's top blocks. */
        fun footprint(clipboard: Clipboard): Result<Footprint> {
            val origin = clipboard.origin
            val min = clipboard.region.minimumPoint
            val max = clipboard.region.maximumPoint
            val width = max.x() - min.x() + 1
            val length = max.z() - min.z() + 1
            val anyTop = IntArray(width * length) { Int.MIN_VALUE }
            val colorTop = IntArray(width * length) { Int.MIN_VALUE }
            val colorRgb = IntArray(width * length)
            var empty = true
            val banned = sortedSetOf<String>()
            for (pos in clipboard.region) {
                val type = clipboard.getBlock(pos).blockType
                if (type.material.isAir) continue
                val id = type.id()
                if (PasteOrders.isOre(id)) {
                    banned += id.removePrefix("minecraft:")
                    continue
                }
                empty = false
                val i = (pos.x() - min.x()) * length + (pos.z() - min.z())
                val y = pos.y() - origin.y()
                if (y > anyTop[i]) anyTop[i] = y
                val rgb = color(id)
                if (rgb >= 0 && y > colorTop[i]) {
                    colorTop[i] = y
                    colorRgb[i] = rgb
                }
            }
            if (banned.isNotEmpty()) {
                return Result.failure(
                    IllegalArgumentException(
                        "Schematics can't contain ores or mineral blocks. Remove these and upload again:\n" +
                            banned.joinToString("\n") { "• $it" },
                    ),
                )
            }
            if (empty) return Result.failure(IllegalArgumentException("The schematic contains no blocks."))
            return Result.success(Footprint(min.x() - origin.x(), min.z() - origin.z(), width, length, anyTop, colorTop, colorRgb))
        }

        private val colorCache = ConcurrentHashMap<String, Int>()

        /** Map color of a block id, or -1 for blocks with none (glass, air-like). */
        private fun color(id: String): Int =
            colorCache.getOrPut(id) {
                val index = Block.fromKey(id)?.registry()?.mapColorId() ?: 0
                if (index in 1 until PALETTE.size) PALETTE[index] else -1
            }

        /** Highest block with a map color in a column, from the heightmap, falling back to a full scan when it looks unset. */
        private fun surface(
            chunk: Chunk,
            bx: Int,
            bz: Int,
            minY: Int,
            maxY: Int,
        ): Int {
            val hinted = chunk.worldSurfaceHeightmap().getHeight(bx and 15, bz and 15)
            val start = if (hinted in (minY + 1)..maxY) minOf(hinted + 1, maxY) else maxY
            for (y in start downTo minY) {
                val block = chunk.getBlock(bx, y, bz, Block.Getter.Condition.TYPE)
                if (!block.air() && color(block.key().asString()) >= 0) return y
            }
            return Int.MIN_VALUE
        }

        /**
         * Renders the world around an origin at (x, y, z) with the schematic drawn where it would land.
         * Runs off the game thread: it loads the chunks it needs, and the server's chunk unloader frees them later.
         */
        fun render(
            world: Instance,
            bounds: IntArray,
            /** The schematic to draw with its origin at (x, y, z), or null for the world alone. */
            footprint: Footprint?,
            x: Int,
            y: Int,
            z: Int,
            filled: Set<Long>,
            own: Set<Long>,
            claimed: Set<Long>,
        ): ChunkMap {
            val (minCx, minCz, cols, rows) = bounds
            val width = cols * 16
            val length = rows * 16
            val scale = (960 / maxOf(width, length)).coerceIn(1, 8)
            val topY = Array(width) { IntArray(length) { Int.MIN_VALUE } }
            val color = Array(width) { IntArray(length) { -1 } }
            // Columns whose top is part of the schematic.
            val build = Array(width) { BooleanArray(length) }
            val dimension = world.cachedDimensionType
            val minY = dimension.minY()
            val maxY = minY + dimension.height() - 1
            // Load in parallel batches: fast, without asking the live server for a thousand chunks at once.
            val loaded = arrayOfNulls<Chunk>(rows * cols)
            (0 until rows * cols).chunked(LOAD_BATCH).forEach { batch ->
                batch
                    .map { it to world.loadChunk(minCx + it % cols, minCz + it / cols) }
                    .forEach { (i, future) -> loaded[i] = future.join() }
            }
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    val chunk = loaded[row * cols + col]!!
                    for (lx in 0 until 16) {
                        for (lz in 0 until 16) {
                            val bx = ((minCx + col) shl 4) + lx
                            val bz = ((minCz + row) shl 4) + lz
                            val top = surface(chunk, bx, bz, minY, maxY)
                            if (top == Int.MIN_VALUE) continue
                            topY[col * 16 + lx][row * 16 + lz] = top
                            color[col * 16 + lx][row * 16 + lz] =
                                color(chunk.getBlock(bx, top, bz, Block.Getter.Condition.TYPE).key().asString())
                        }
                    }
                }
            }
            // The schematic replaces the world wherever it reaches at least as high as what stands there.
            if (footprint != null) {
                draw(footprint, x, y, z, minCx, minCz, topY, color, build)
            }
            val image = BufferedImage(width * scale, length * scale, BufferedImage.TYPE_INT_RGB)
            val pixels = IntArray(image.width * image.height) { 0x1E1F22 }
            for (bx in 0 until width) {
                for (bz in 0 until length) {
                    val rgb = color[bx][bz]
                    if (rgb < 0) continue
                    val north = if (bz > 0) topY[bx][bz - 1] else topY[bx][bz]
                    // The surrounding world is dimmed so the schematic stands out.
                    val shade =
                        when {
                            topY[bx][bz] > north -> 1.0
                            topY[bx][bz] == north -> 0.86
                            else -> 0.71
                        } * if (footprint == null || build[bx][bz]) 1.0 else 0.6
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
            return ChunkMap(minCx, minCz, cols, rows, scale, image, filled, own, claimed)
        }

        private fun draw(
            footprint: Footprint,
            x: Int,
            y: Int,
            z: Int,
            minCx: Int,
            minCz: Int,
            topY: Array<IntArray>,
            color: Array<IntArray>,
            build: Array<BooleanArray>,
        ) {
            for (fx in 0 until footprint.width) {
                for (fz in 0 until footprint.length) {
                    val i = fx * footprint.length + fz
                    val any = footprint.anyTop[i]
                    if (any == Int.MIN_VALUE) continue
                    val colored = footprint.colorTop[i]
                    val bx = x + footprint.minX + fx - (minCx shl 4)
                    val bz = z + footprint.minZ + fz - (minCz shl 4)
                    val ground = topY[bx][bz]
                    // Colorless blocks (glass) only show where they stand above everything colored.
                    if (any > colored && any + y > ground) {
                        topY[bx][bz] = any + y
                        color[bx][bz] = 0x909090
                        build[bx][bz] = true
                    } else if (colored != Int.MIN_VALUE && colored + y >= ground) {
                        topY[bx][bz] = colored + y
                        color[bx][bz] = footprint.colorRgb[i]
                        build[bx][bz] = true
                    }
                }
            }
        }
    }
}

/** The schematic seen from above, relative to its origin; built once per upload so each placement is cheap. */
internal class Footprint(
    val minX: Int,
    val minZ: Int,
    val width: Int,
    val length: Int,
    /** Highest non-air block per column (index x * length + z), relative to the origin; Int.MIN_VALUE when empty. */
    val anyTop: IntArray,
    /** Highest block with a map color per column; Int.MIN_VALUE when none. */
    val colorTop: IntArray,
    val colorRgb: IntArray,
) {
    /** This footprint turned clockwise (seen from above) [times] quarter turns about the origin, as WorldEdit rotates. */
    fun rotated(times: Int): Footprint {
        var f = this
        repeat(Math.floorMod(times, 4)) {
            val any = IntArray(f.anyTop.size)
            val top = IntArray(f.anyTop.size)
            val rgb = IntArray(f.anyTop.size)
            // (x, z) turns to (-z, x).
            for (fx in 0 until f.width) {
                for (fz in 0 until f.length) {
                    val from = fx * f.length + fz
                    val to = (f.length - 1 - fz) * f.width + fx
                    any[to] = f.anyTop[from]
                    top[to] = f.colorTop[from]
                    rgb[to] = f.colorRgb[from]
                }
            }
            f = Footprint(-(f.minZ + f.length - 1), f.minX, f.length, f.width, any, top, rgb)
        }
        return f
    }

    /** World chunks holding at least one block when the origin is at (x, z). */
    fun chunks(
        x: Int,
        z: Int,
    ): Set<Long> {
        val chunks = HashSet<Long>()
        for (fx in 0 until width) {
            for (fz in 0 until length) {
                if (anyTop[fx * length + fz] != Int.MIN_VALUE) chunks += ChunkMap.pack((x + minX + fx) shr 4, (z + minZ + fz) shr 4)
            }
        }
        return chunks
    }
}
