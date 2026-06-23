package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.TreeFellerListener
import net.minestom.server.coordinate.Point
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block

// https://github.com/IDev-mc/TreeFeller
object TreeFeller {
    val logs =
        listOf(
            Block.OAK_LOG,
            Block.SPRUCE_LOG,
            Block.BIRCH_LOG,
            Block.JUNGLE_LOG,
            Block.ACACIA_LOG,
            Block.DARK_OAK_LOG,
            Block.CHERRY_LOG,
            Block.MANGROVE_LOG,
            Block.PALE_OAK_LOG,
            Block.BAMBOO_BLOCK,
            Block.CRIMSON_STEM,
            Block.WARPED_STEM,
        )

    private val leaves =
        listOf(
            Block.OAK_LEAVES,
            Block.SPRUCE_LEAVES,
            Block.BIRCH_LEAVES,
            Block.JUNGLE_LEAVES,
            Block.ACACIA_LEAVES,
            Block.DARK_OAK_LEAVES,
            Block.CHERRY_LEAVES,
            Block.MANGROVE_LEAVES,
            Block.PALE_OAK_LEAVES,
            Block.AZALEA_LEAVES,
            Block.FLOWERING_AZALEA_LEAVES,
            Block.NETHER_WART_BLOCK,
            Block.WARPED_WART_BLOCK,
        )

    fun isLog(block: Block) = logs.any { block.compare(it) }

    private fun isLeaf(block: Block) = leaves.any { block.compare(it) }

    fun isTree(
        origin: Point,
        instance: Instance,
        logBlock: Block,
    ): Boolean {
        val maxHeight = Vanilla.config!!.treeFellerMaxHeight
        val x = origin.blockX()
        val z = origin.blockZ()
        var y = origin.blockY()
        var scanned = 0
        while (scanned++ < maxHeight) {
            for (dx in -1..1) {
                for (dz in -1..1) {
                    if (isLeaf(instance.getBlock(x + dx, y, z + dz))) return true
                }
            }
            if (!instance.getBlock(x, y + 1, z).compare(logBlock)) break
            y++
        }
        return false
    }

    fun getTree(
        origin: Point,
        instance: Instance,
        logBlock: Block,
    ): List<Triple<Int, Int, Int>> {
        val maxSize = Vanilla.config!!.treeFellerMaxSize
        val found = mutableListOf(Triple(origin.blockX(), origin.blockY(), origin.blockZ()))
        val visited = HashSet(found)
        var i = 0
        while (i < found.size) {
            if (found.size >= maxSize) return emptyList()
            val (cx, cy, cz) = found[i++]
            for (dx in -1..1) {
                for (dy in -1..1) {
                    for (dz in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0) continue
                        val key = Triple(cx + dx, cy + dy, cz + dz)
                        if (key in visited) continue
                        visited.add(key)
                        if (instance.getBlock(key.first, key.second, key.third).compare(logBlock)) {
                            found.add(key)
                        }
                    }
                }
            }
        }
        return found
    }

    fun init() {
        val timeStart = System.currentTimeMillis()
        TreeFellerListener.init()
        val timeEnd = System.currentTimeMillis()
        println("TreeFeller enabled in ${timeEnd - timeStart}ms")
    }
}
