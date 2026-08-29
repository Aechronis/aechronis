package net.aechronis.vanilla.managers

import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.TreeFellerListener
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockTags
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.WorldEventPacket
import net.minestom.server.registry.TagKey
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.max

// https://github.com/IDev-mc/TreeFeller
object TreeFeller {
    private data class FellingPosition(
        val instance: Instance,
        val x: Int,
        val y: Int,
        val z: Int,
    )

    private val activePositions = ConcurrentHashMap.newKeySet<FellingPosition>()

    val logs: List<Block> by lazy {
        MinecraftServer
            .process()
            .blocks()
            .values()
            .filter(::isLog)
    }

    private val saplingsByLogTag =
        listOf(
            BlockTags.OAK_LOGS to Material.OAK_SAPLING,
            BlockTags.SPRUCE_LOGS to Material.SPRUCE_SAPLING,
            BlockTags.BIRCH_LOGS to Material.BIRCH_SAPLING,
            BlockTags.JUNGLE_LOGS to Material.JUNGLE_SAPLING,
            BlockTags.ACACIA_LOGS to Material.ACACIA_SAPLING,
            BlockTags.DARK_OAK_LOGS to Material.DARK_OAK_SAPLING,
            BlockTags.CHERRY_LOGS to Material.CHERRY_SAPLING,
            BlockTags.MANGROVE_LOGS to Material.MANGROVE_PROPAGULE,
            BlockTags.PALE_OAK_LOGS to Material.PALE_OAK_SAPLING,
        )

    private val ADJACENT_OFFSETS =
        buildList {
            for (dx in -1..1) {
                for (dy in -1..1) {
                    for (dz in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0) continue
                        add(Triple(dx, dy, dz))
                    }
                }
            }
        }

    fun isLog(block: Block) = blockIsInTag(block, BlockTags.LOGS) || blockIsInTag(block, BlockTags.BAMBOO_BLOCKS)

    internal fun isLeaf(block: Block) = blockIsInTag(block, BlockTags.LEAVES) || blockIsInTag(block, BlockTags.WART_BLOCKS)

    private fun blockIsInTag(
        block: Block,
        tag: TagKey<Block>,
    ): Boolean =
        MinecraftServer
            .process()
            .blocks()
            .getTag(tag)
            ?.contains(block) == true

    fun isTree(
        origin: Point,
        instance: Instance,
        logBlock: Block,
    ): Boolean {
        if (!isLog(logBlock)) return false
        val maxDistance = Vanilla.config.treeFellerMaxHeight.coerceAtLeast(0)
        val maxSize = Vanilla.config.treeFellerMaxSize.coerceAtLeast(1)
        val originPosition = Triple(origin.blockX(), origin.blockY(), origin.blockZ())
        val visited = hashSetOf(originPosition)
        val queue = ArrayDeque<Pair<Triple<Int, Int, Int>, Int>>()
        queue.add(originPosition to 0)

        while (queue.isNotEmpty()) {
            val (position, distance) = queue.removeFirst()
            val (x, y, z) = position
            for ((dx, dy, dz) in ADJACENT_OFFSETS) {
                val adjacent = Triple(x + dx, y + dy, z + dz)
                val adjacentBlock = instance.getBlock(adjacent.first, adjacent.second, adjacent.third)
                if (isLeaf(adjacentBlock)) return true
                if (distance < maxDistance && isLog(adjacentBlock) && visited.add(adjacent)) {
                    if (visited.size >= maxSize) return false
                    queue.add(adjacent to distance + 1)
                }
            }
        }
        return false
    }

    fun getTree(
        origin: Point,
        instance: Instance,
        logBlock: Block,
    ): List<Triple<Int, Int, Int>> {
        if (!isLog(logBlock)) return emptyList()
        val maxSize = Vanilla.config.treeFellerMaxSize
        val found = mutableListOf(Triple(origin.blockX(), origin.blockY(), origin.blockZ()))
        val visited = HashSet(found)
        var i = 0
        while (i < found.size) {
            if (found.size >= maxSize) return emptyList()
            val (cx, cy, cz) = found[i++]
            for ((dx, dy, dz) in ADJACENT_OFFSETS) {
                val key = Triple(cx + dx, cy + dy, cz + dz)
                if (key in visited) continue
                visited.add(key)
                if (isLog(instance.getBlock(key.first, key.second, key.third))) {
                    found.add(key)
                }
            }
        }
        return found
    }

    fun collectLeaves(
        logs: List<Triple<Int, Int, Int>>,
        instance: Instance,
    ): List<Triple<Int, Int, Int>> {
        val maxDistance = Vanilla.config.treeFellerLeafMaxDistance
        val maxLeaves = Vanilla.config.treeFellerMaxLeaves
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
            for ((dx, dy, dz) in ADJACENT_OFFSETS) {
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
            for ((dx, dy, dz) in ADJACENT_OFFSETS) {
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

    fun saplingMaterial(logBlock: Block): Material? = saplingsByLogTag.firstOrNull { (tag, _) -> blockIsInTag(logBlock, tag) }?.second

    private fun rollLeafDrop(saplingMaterial: Material?): List<ItemStack> {
        val saplingChance = Vanilla.config.treeFellerSaplingChance
        val stickChance = Vanilla.config.treeFellerStickChance
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
        val candidates =
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
        val ordered =
            candidates.filter { (pos, _) ->
                val (x, y, z) = pos
                activePositions.add(FellingPosition(instance, x, y, z))
            }
        if (ordered.isEmpty()) return

        val saplingMaterial = saplingMaterial(logBlock)
        val perTick = Vanilla.config.treeFellerBlocksPerTick.coerceAtLeast(1)
        val interval = Vanilla.config.treeFellerTickInterval.coerceAtLeast(1)

        var index = 0
        ModuleScheduler.submitTask {
            var done = 0
            while (done < perTick && index < ordered.size) {
                val (pos, leaf) = ordered[index++]
                done++
                val (x, y, z) = pos
                val fellingPosition = FellingPosition(instance, x, y, z)
                val current = instance.getBlock(x, y, z)
                val isExpected = if (leaf) isLeaf(current) else isLog(current)
                if (!isExpected) {
                    activePositions.remove(fellingPosition)
                    continue
                }
                val stateId = current.stateId()
                instance.setBlock(x, y, z, Block.AIR)
                activePositions.remove(fellingPosition)
                instance
                    .getChunk(x shr 4, z shr 4)
                    ?.sendPacketToViewers(
                        WorldEventPacket(2001, BlockVec(x, y, z), stateId, false),
                    )
                val drops =
                    if (leaf) {
                        rollLeafDrop(saplingMaterial)
                    } else {
                        current.registry()?.material()?.let { listOf(ItemStack.of(it)) }
                    }
                if (!drops.isNullOrEmpty()) {
                    val dropPos = Pos(x + 0.5, y + 0.5, z + 0.5)
                    for (stack in drops) Items.spawn(instance, dropPos, stack)
                }
            }
            if (index >= ordered.size) TaskSchedule.stop() else TaskSchedule.tick(interval)
        }
    }

    fun init() {
        val timeStart = System.currentTimeMillis()
        TreeFellerListener.init()
        val timeEnd = System.currentTimeMillis()
        println("├─ TreeFeller enabled in ${timeEnd - timeStart}ms")
    }
}
