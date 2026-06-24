package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.TreeFellerListener
import net.aechronis.vanilla.objects.SaplingType
import net.aechronis.vanilla.utils.PlayerAddons.giveDrops
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.WorldEventPacket
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.max

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

    private val ORTHOGONAL =
        listOf(
            Triple(1, 0, 0),
            Triple(-1, 0, 0),
            Triple(0, 1, 0),
            Triple(0, -1, 0),
            Triple(0, 0, 1),
            Triple(0, 0, -1),
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

    fun collectLeaves(
        logs: List<Triple<Int, Int, Int>>,
        instance: Instance,
    ): List<Triple<Int, Int, Int>> {
        val maxDistance = Vanilla.config!!.treeFellerLeafMaxDistance
        val maxLeaves = Vanilla.config!!.treeFellerMaxLeaves
        val logSet = HashSet(logs)

        fun distanceToNearestLog(
            x: Int,
            y: Int,
            z: Int,
        ): Int {
            var best = Int.MAX_VALUE
            for ((lx, ly, lz) in logs) {
                val d = max(abs(x - lx), max(abs(y - ly), abs(z - lz)))
                if (d < best) best = d
                if (best == 0) break
            }
            return best
        }

        val found = mutableListOf<Triple<Int, Int, Int>>()
        val visited = HashSet<Triple<Int, Int, Int>>()
        val queue = ArrayDeque<Triple<Int, Int, Int>>()

        for ((lx, ly, lz) in logs) {
            for ((dx, dy, dz) in ORTHOGONAL) {
                val key = Triple(lx + dx, ly + dy, lz + dz)
                if (key in logSet || key in visited) continue
                visited.add(key)
                if (isLeaf(instance.getBlock(key.first, key.second, key.third))) {
                    found.add(key)
                    queue.add(key)
                }
            }
        }

        while (queue.isNotEmpty()) {
            if (found.size >= maxLeaves) break
            val (cx, cy, cz) = queue.removeFirst()
            for ((dx, dy, dz) in ORTHOGONAL) {
                val key = Triple(cx + dx, cy + dy, cz + dz)
                if (key in logSet || key in visited) continue
                visited.add(key)
                if (distanceToNearestLog(key.first, key.second, key.third) > maxDistance) continue
                if (isLeaf(instance.getBlock(key.first, key.second, key.third))) {
                    found.add(key)
                    queue.add(key)
                }
            }
        }
        return found
    }

    fun saplingMaterial(logBlock: Block): Material? =
        SaplingType.ALL
            .firstOrNull { it.logBlock.compare(logBlock) }
            ?.saplingBlock
            ?.registry()
            ?.material()

    private fun rollLeafDrop(saplingMaterial: Material?): List<ItemStack> {
        val saplingChance = Vanilla.config!!.treeFellerSaplingChance
        val stickChance = Vanilla.config!!.treeFellerStickChance
        val roll = ThreadLocalRandom.current().nextDouble()
        return when {
            saplingMaterial != null && roll < saplingChance -> listOf(ItemStack.of(saplingMaterial))
            roll < saplingChance + stickChance -> listOf(ItemStack.of(Material.STICK, 2))
            else -> emptyList()
        }
    }

    fun fell(
        player: Player,
        instance: Instance,
        logs: List<Triple<Int, Int, Int>>,
        leaves: List<Triple<Int, Int, Int>>,
        logBlock: Block,
    ) {
        val ox = player.position.blockX()
        val oz = player.position.blockZ()
        val ordered =
            (logs.map { it to false } + leaves.map { it to true })
                .sortedWith(
                    compareBy(
                        { it.first.second },
                        {
                            val (x, _, z) = it.first
                            abs(x - ox) + abs(z - oz)
                        },
                    ),
                )
        if (ordered.isEmpty()) return

        val logMaterial = logBlock.registry()?.material()
        val saplingMaterial = saplingMaterial(logBlock)
        val perTick = Vanilla.config!!.treeFellerBlocksPerTick.coerceAtLeast(1)
        val interval = Vanilla.config!!.treeFellerTickInterval.coerceAtLeast(1)

        var index = 0
        MinecraftServer.getSchedulerManager().submitTask {
            var done = 0
            while (done < perTick && index < ordered.size) {
                val (pos, leaf) = ordered[index++]
                done++
                val (x, y, z) = pos
                val stateId = instance.getBlock(x, y, z).stateId()
                instance.setBlock(x, y, z, Block.AIR)
                instance
                    .getChunk(x shr 4, z shr 4)
                    ?.sendPacketToViewers(
                        WorldEventPacket(2001, BlockVec(x, y, z), stateId, false),
                    )
                val drops = if (leaf) rollLeafDrop(saplingMaterial) else logMaterial?.let { listOf(ItemStack.of(it)) }
                if (!drops.isNullOrEmpty()) player.giveDrops(drops)
            }
            if (index >= ordered.size) TaskSchedule.stop() else TaskSchedule.tick(interval)
        }
    }

    fun init() {
        val timeStart = System.currentTimeMillis()
        TreeFellerListener.init()
        val timeEnd = System.currentTimeMillis()
        println("TreeFeller enabled in ${timeEnd - timeStart}ms")
    }
}
