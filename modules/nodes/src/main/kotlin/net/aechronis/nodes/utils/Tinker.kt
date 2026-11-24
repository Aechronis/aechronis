/**
 * Tinker rules (like Axiom's tinker tool): what an empty-hand right-click does to a block,
 * based on the face and spot that was clicked. The /tinker listener applies the result.
 *
 * Stairs, slabs and full blocks of the same material are treated as 8 small cubes (octants).
 * A click removes or adds one octant, and the new shape is turned back into a full block,
 * a slab or a stair (including inner and outer corners).
 */

package net.aechronis.nodes.utils

import net.minestom.server.coordinate.BlockVec
import net.minestom.server.instance.block.Block
import net.minestom.server.utils.Direction
import java.util.concurrent.ConcurrentHashMap

internal class TinkerHit(
    val pos: BlockVec,
    val block: Block,
    val face: Direction,
    /** Where inside the block the click landed, 0 to 1 on each axis. */
    val rx: Double,
    val ry: Double,
    val rz: Double,
    val eyeDistance: Double,
    val blockAt: (BlockVec) -> Block,
)

internal object Tinker {
    /** Builds the stair and slab table at startup so the first click is not slow. */
    fun warmUp() {
        families.size
    }

    /** Blocks to change (position to new block). Empty if nothing would change. */
    fun apply(hit: TinkerHit): Map<BlockVec, Block> {
        val changes = HashMap<BlockVec, Block>()
        val updated = rules(hit, changes) ?: return emptyMap()
        if (updated != hit.block) changes[hit.pos] = updated
        return changes
    }

    private fun name(block: Block) = block.name().removePrefix("minecraft:")

    private fun rules(h: TinkerHit, extra: MutableMap<BlockVec, Block>): Block? {
        val b = h.block
        val n = name(b)
        val face = h.face
        return when {
            n == "piston" || n == "sticky_piston" -> piston(h, extra)
            n == "piston_head" -> pistonHead(h, extra)
            n.endsWith("_door") && !n.startsWith("iron") -> door(h, extra)
            n.endsWith("_trapdoor") && !n.startsWith("iron") -> b.cycle("open")
            n.endsWith("_fence_gate") -> if (face == Direction.UP) b.cycle("in_wall") else b.cycle("open")
            n.endsWith("glazed_terracotta") -> b.cycle("facing")
            n in FACING_BLOCKS -> facingBlock(b, n, face)
            n.endsWith("_fence") || n.endsWith("_pane") || n.endsWith("_bars") -> connection(h)
            n.endsWith("_wall") -> wall(h)
            isStair(b) -> stair(h)
            isSlab(b) -> slab(h)
            else -> generic(h)
        }
    }

    private fun piston(h: TinkerHit, extra: MutableMap<BlockVec, Block>): Block {
        val b = h.block
        val facing = Direction.valueOf(b.getProperty("facing")!!.uppercase())
        val sticky = name(b) == "sticky_piston"
        val extended = b.getProperty("extended") == "true"
        val headPos = h.pos.offset(facing)
        if (facing == h.face) {
            // do not replace a block that is in the way
            if (extended || !h.blockAt(headPos).compare(Block.AIR)) return b
            extra[headPos] = Block.PISTON_HEAD
                .withProperty("type", if (sticky) "sticky" else "normal")
                .withProperty("facing", facing.lower())
            return b.withProperty("extended", "true")
        }
        var out = b
        if (extended) {
            val head = h.blockAt(headPos)
            if (name(head) == "piston_head" && head.getProperty("facing") == facing.lower()) extra[headPos] = Block.AIR
            out = out.withProperty("extended", "false")
        }
        return out.withProperty("facing", h.face.lower())
    }

    private fun pistonHead(h: TinkerHit, extra: MutableMap<BlockVec, Block>): Block {
        val b = h.block
        val facing = Direction.valueOf(b.getProperty("facing")!!.uppercase())
        val basePos = h.pos.offset(facing.opposite())
        val base = h.blockAt(basePos)
        val baseName = if (b.getProperty("type") == "sticky") "sticky_piston" else "piston"
        if (name(base) == baseName && base.getProperty("extended") == "true" && base.getProperty("facing") == facing.lower()) {
            extra[basePos] = base.withProperty("extended", "false")
            return Block.AIR
        }
        return b.withProperty("facing", h.face.lower())
    }

    private fun door(h: TinkerHit, extra: MutableMap<BlockVec, Block>): Block {
        val b = h.block
        val open = if (b.getProperty("open") == "true") "false" else "true"
        val otherPos = h.pos.offset(if (b.getProperty("half") == "upper") Direction.DOWN else Direction.UP)
        val other = h.blockAt(otherPos)
        if (name(other).endsWith("_door")) extra[otherPos] = other.withProperty("open", open)
        return b.withProperty("open", open)
    }

    private val FACING_BLOCKS = setOf(
        "chest", "trapped_chest", "ender_chest", "furnace", "blast_furnace", "smoker",
        "dispenser", "dropper", "barrel", "observer", "carved_pumpkin", "jack_o_lantern",
    )

    // Click a side to turn the block to face it. Clicking the side it already faces toggles lit, open or triggered.
    private fun facingBlock(b: Block, n: String, face: Direction): Block? {
        // changing one half of a double chest would split the pair
        if (b.getProperty("type").let { it != null && it != "single" }) return null
        val target = (if (n == "observer") face.opposite() else face).lower()
        if (target !in b.values("facing")) return null
        if (b.getProperty("facing") != target) return b.withProperty("facing", target)
        return listOf("lit", "open", "triggered").firstNotNullOfOrNull { b.cycle(it) }
    }

    // Fences, panes and bars: toggle the arm closest to the click.
    private fun connection(h: TinkerHit): Block? {
        val ax = kotlin.math.abs(h.rx - 0.5)
        val az = kotlin.math.abs(h.rz - 0.5)
        if (h.face.axis() == "y" && maxOf(ax, az) < 0.125) return null
        val side = if (ax > az) (if (h.rx > 0.5) "east" else "west") else (if (h.rz > 0.5) "south" else "north")
        return h.block.cycle(side)
    }

    private fun wall(h: TinkerHit): Block? {
        var b = h.block
        val ax = kotlin.math.abs(h.rx - 0.5)
        val az = kotlin.math.abs(h.rz - 0.5)
        val top = h.face.axis() == "y"
        b = if (ax > az) {
            if (top && ax < 0.25) b.cycle("up") else b.cycle(if (h.rx > 0.5) "east" else "west")
        } else {
            if (top && az < 0.25) b.cycle("up") else b.cycle(if (h.rz > 0.5) "south" else "north")
        } ?: return null
        // keep the post if the wall has no arms
        val arms = listOf("east", "west", "south", "north").all { b.getProperty(it) == "none" }
        return if (arms && b.getProperty("up") == "false") b.withProperty("up", "true") else b
    }

    private val GENERIC = listOf("lit", "attached", "layers", "bites", "rotation", "hanging")
    private val NO_POWER = listOf("door", "lever", "repeater", "comparator", "button", "pressure_plate", "fence_gate")

    private fun generic(h: TinkerHit): Block? {
        val b = h.block
        val n = name(b)
        if (b.getProperty("axis") != null) return if (h.face.axis() in b.values("axis")) b.withProperty("axis", h.face.axis()) else null
        GENERIC.firstOrNull { b.getProperty(it) != null }?.let { return b.cycle(it) }
        if (b.getProperty("powered") != null) return if (NO_POWER.none { n.contains(it) }) b.cycle("powered") else null
        return carveFull(h)
    }

    private class Family(val full: Block, val stair: Block, val slab: Block)

    private val families: Map<Int, Family> by lazy {
        val map = HashMap<Int, Family>()
        for (stair in Block.values()) {
            val n = name(stair)
            if (!n.endsWith("_stairs")) continue
            val base = n.removeSuffix("_stairs")
            val slab = Block.fromKey("minecraft:${base}_slab") ?: continue
            val full = listOf(base, "${base}s", "${base}_planks", "${base}_block")
                .firstNotNullOfOrNull { Block.fromKey("minecraft:$it") } ?: continue
            val family = Family(full, stair, slab)
            map[full.id()] = family
            map[stair.id()] = family
            map[slab.id()] = family
        }
        map
    }

    private fun isStair(b: Block) = name(b).endsWith("_stairs") && families.containsKey(b.id())
    private fun isSlab(b: Block) = name(b).endsWith("_slab") && families.containsKey(b.id())

    // Bit for one octant in a layer: +x +z is bit 0, +x -z is bit 1, -x +z is bit 2, -x -z is bit 3.
    private fun layerBit(sx: Int, sz: Int) = 1 shl ((if (sx > 0) 0 else 2) + (if (sz > 0) 0 else 1))

    private fun facingX(f: String) = when (f) {
        "east" -> 1
        "west" -> -1
        else -> 0
    }
    private fun facingZ(f: String) = when (f) {
        "south" -> 1
        "north" -> -1
        else -> 0
    }

    // The octants a stair fills in its stepped layer. Left of the facing direction is (fz, -fx).
    private fun stairLayer(facing: String, shape: String): Int {
        val fx = facingX(facing)
        val fz = facingZ(facing)
        val lx = fz
        val lz = -fx
        val straight = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
            .filter { (sx, sz) -> sx * fx + sz * fz > 0 }
            .fold(0) { acc, (sx, sz) -> acc or layerBit(sx, sz) }
        return when (shape) {
            "outer_left" -> layerBit(fx + lx, fz + lz)
            "outer_right" -> layerBit(fx - lx, fz - lz)
            "inner_left" -> straight or layerBit(-fx + lx, -fz + lz)
            "inner_right" -> straight or layerBit(-fx - lx, -fz - lz)
            else -> straight
        }
    }

    private fun stairVoxel(facing: String, half: String, shape: String): Int {
        val layer = stairLayer(facing, shape)
        return if (half == "bottom") 0xF0 or layer else 0x0F or (layer shl 4)
    }

    private val STAIR_BY_VOXEL: Map<Int, Map<String, String>> = buildMap {
        for (facing in listOf("north", "south", "east", "west")) {
            for (half in listOf("bottom", "top")) {
                for (shape in listOf("straight", "inner_left", "inner_right", "outer_left", "outer_right")) {
                    put(stairVoxel(facing, half, shape), mapOf("facing" to facing, "half" to half, "shape" to shape))
                }
            }
        }
    }

    private fun voxelOf(b: Block): Int = when {
        isStair(b) -> stairVoxel(b.getProperty("facing")!!, b.getProperty("half")!!, b.getProperty("shape")!!)
        isSlab(b) -> when (b.getProperty("type")) {
            "bottom" -> 0xF0
            "top" -> 0x0F
            else -> 0xFF
        }
        else -> 0xFF
    }

    private fun fromVoxel(voxel: Int, family: Family, old: Block): Block? {
        val out = when (voxel) {
            0 -> return Block.AIR
            0xFF -> family.full
            0xF0 -> family.slab.withProperty("type", "bottom")
            0x0F -> family.slab.withProperty("type", "top")
            else -> family.stair.withProperties(STAIR_BY_VOXEL[voxel] ?: return null)
        }
        val wet = old.getProperty("waterlogged")
        return if (wet != null && out.getProperty("waterlogged") != null) out.withProperty("waterlogged", wet) else out
    }

    // Octant number (0 to 7) for a point inside the block.
    private fun octant(x: Double, y: Double, z: Double) = (if (z <= 0.5) 1 else 0) + (if (x <= 0.5) 2 else 0) + (if (y <= 0.5) 4 else 0)

    private fun stair(h: TinkerHit): Block? {
        val family = families[h.block.id()] ?: return null
        val voxel = voxelOf(h.block)
        val f = h.face
        val step = 0.25
        // remove the octant behind the clicked face, or add the one in front if that shape is not valid
        // (on an outer face the one in front is outside the block, so nothing is added there)
        val behind = octant(h.rx - f.normalX() * step, h.ry - f.normalY() * step, h.rz - f.normalZ() * step)
        fromVoxel(voxel and (1 shl behind).inv(), family, h.block)?.let { return it }
        val front = octant(h.rx + f.normalX() * step, h.ry + f.normalY() * step, h.rz + f.normalZ() * step)
        return fromVoxel(voxel or (1 shl front), family, h.block)
    }

    private fun slab(h: TinkerHit): Block? {
        val family = families[h.block.id()] ?: return null
        // a single slab can only be clicked on its flat top or bottom
        val offsetY = h.ry + h.face.normalY() * 0.25
        if (h.block.getProperty("type") != "double" && (offsetY < 0.0 || offsetY > 1.0)) return null
        var index = (if (h.rz <= 0.5) 1 else 0) + (if (h.rx <= 0.5) 2 else 0)
        val voxel = when (h.block.getProperty("type")) {
            "top" -> if (h.face.axis() != "y") return null else 0x0F or (1 shl (index + 4))
            "bottom" -> if (h.face.axis() != "y") return null else 0xF0 or (1 shl index)
            else -> {
                if (h.ry <= 0.5) index += 4
                0xFF and (1 shl index).inv()
            }
        }
        return fromVoxel(voxel, family, h.block)
    }

    // For plain full blocks that have stairs and slabs: cut out the clicked octant.
    private fun carveFull(h: TinkerHit): Block? {
        val family = families[h.block.id()] ?: return null
        if (h.eyeDistance < 0.5) return null
        val index = octant(h.rx, h.ry, h.rz)
        return fromVoxel(0xFF and (1 shl index).inv(), family, h.block)
    }

    private fun Direction.lower() = name.lowercase()

    private fun Direction.axis() = when {
        normalX() != 0 -> "x"
        normalY() != 0 -> "y"
        else -> "z"
    }

    private fun BlockVec.offset(d: Direction) = BlockVec(blockX() + d.normalX(), blockY() + d.normalY(), blockZ() + d.normalZ())

    // Property values per block type, cached because possibleStates() is slow for blocks with many states.
    private val valueCache = ConcurrentHashMap<Long, List<String>>()

    private fun Block.values(property: String): List<String> = valueCache.computeIfAbsent(id().toLong() shl 32 or property.hashCode().toLong().and(0xFFFFFFFFL)) {
        val values = possibleStates().mapNotNull { it.getProperty(property) }.distinct()
        if (values.all { it.toIntOrNull() != null }) values.sortedBy { it.toInt() } else values
    }

    private fun Block.cycle(property: String): Block? {
        val values = values(property)
        if (values.size < 2) return null
        return withProperty(property, values[(values.indexOf(getProperty(property)) + 1) % values.size])
    }
}
