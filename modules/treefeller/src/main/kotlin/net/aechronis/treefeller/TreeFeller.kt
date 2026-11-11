package net.aechronis.treefeller

import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.treefeller.listeners.TreeFellerListener
import net.aechronis.vanilla.managers.Items
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

// Detection ported from Thizzy'z Tree Feller: https://github.com/ThizThizzyDizzy/tree-feller (GPL-3.0)
object TreeFeller {
    internal var config = TreeFellerConfig()
        private set

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

    private val FACE_OFFSETS =
        listOf(Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0), Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1))

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

    // Unloaded chunks make Instance.getBlock throw; treat them as air so detection stops at the edge.
    private fun blockAt(
        instance: Instance,
        pos: Triple<Int, Int, Int>,
    ): Block {
        val (x, y, z) = pos
        return if (instance.isChunkLoaded(x shr 4, z shr 4)) instance.getBlock(x, y, z) else Block.AIR
    }

    // oak_log, oak_wood, stripped_oak_log, stripped_oak_wood -> oak. Custom trees mix all four, while
    // neighbouring trees of another species stay apart.
    internal fun species(block: Block): String =
        block
            .key()
            .value()
            .removePrefix("stripped_")
            .removeSuffix("_log")
            .removeSuffix("_wood")
            .removeSuffix("_stem")
            .removeSuffix("_hyphae")

    // oak_leaves -> oak, so a tree only takes its own leaves and never a neighbour's of another species.
    private val LEAF_SPECIES =
        mapOf(
            "azalea_leaves" to "oak",
            "flowering_azalea_leaves" to "oak",
            "nether_wart_block" to "crimson",
            "warped_wart_block" to "warped",
        )

    internal fun leafSpecies(block: Block): String {
        val name = block.key().value()
        return LEAF_SPECIES[name] ?: name.removeSuffix("_leaves")
    }

    /**
     * The logs (origin first) and leaves to fell when [logBlock] at [origin] is chopped; both empty when it is not a tree.
     * Structure guards and leaf requirement follow Thizzy'z Tree Feller (github.com/ThizThizzyDizzy/tree-feller, GPL-3.0).
     * Trees that touch (or share roots) form one log cluster, so each log goes to the trunk base it is
     * nearest to through the logs and only the chopped trunk's share falls. Logs below the cut (stump,
     * roots) stay.
     */
    fun detect(
        origin: Point,
        instance: Instance,
        logBlock: Block,
    ): Pair<List<Triple<Int, Int, Int>>, List<Triple<Int, Int, Int>>> {
        val none = emptyList<Triple<Int, Int, Int>>() to emptyList<Triple<Int, Int, Int>>()
        if (!isLog(logBlock)) return none
        val cut = Triple(origin.blockX(), origin.blockY(), origin.blockZ())
        val started = System.nanoTime()

        fun reject(reason: String): Pair<List<Triple<Int, Int, Int>>, List<Triple<Int, Int, Int>>> {
            debug("not felling at $cut: $reason (${(System.nanoTime() - started) / 1_000_000} ms)")
            return none
        }
        val cluster = findCluster(cut, instance, species(logBlock)) ?: return reject("over ${config.maxClusterLogs} connected logs")
        val (owner, groundY) = assignTrunks(cluster, instance)
        val tree = owner.getValue(cut)
        // Chopped a root, a branch or high up the trunk (1 = bottom block above ground).
        val cutHeight = cut.second - groundY.getValue(tree) + 1
        if (cutHeight < 1 || cutHeight > config.maxCutHeight) return reject("cut height $cutHeight (trunk ground y ${groundY[tree]})")
        val logs = listOf(cut) + cluster.filter { it != cut && owner[it] == tree && it.second >= cut.second }
        if (logs.size < config.requiredLogs) return reject("${logs.size} logs")
        if (logs.size > config.maxLogs) return reject("${logs.size} logs, over ${config.maxLogs}")
        if (looksLikeStructure(logs, instance)) return reject("looks like a structure")
        // Custom trees pair wood and leaves of different species (spruce wood, oak leaves), so fall back to any leaves.
        val leaves =
            collectLeaves(logs, cluster, owner, tree, instance, species(logBlock)).takeIf { it.size >= config.requiredLeaves }
                ?: collectLeaves(logs, cluster, owner, tree, instance, null).takeIf { it.size >= config.requiredLeaves }
                ?: return reject("under ${config.requiredLeaves} leaves")
        debug(
            "felling at $cut: ${logs.size} logs, ${leaves.size} leaves (cluster ${cluster.size} logs, ${groundY.size} trunks, ${(System.nanoTime() - started) / 1_000_000} ms)",
        )
        return logs to leaves
    }

    private fun debug(message: String) {
        if (config.debug) println("[TreeFeller] $message")
    }

    // Every log of the cut's species connected to it, nearest first. Null when it is a whole forest.
    private fun findCluster(
        cut: Triple<Int, Int, Int>,
        instance: Instance,
        kind: String,
    ): List<Triple<Int, Int, Int>>? {
        val found = mutableListOf(cut)
        val seen = hashSetOf(cut)
        var i = 0
        while (i < found.size) {
            val (cx, cy, cz) = found[i++]
            for ((dx, dy, dz) in ADJACENT_OFFSETS) {
                val key = Triple(cx + dx, cy + dy, cz + dz)
                if (abs(key.first - cut.first) > config.scanRadius || abs(key.third - cut.third) > config.scanRadius) continue
                if (!seen.add(key)) continue
                val block = blockAt(instance, key)
                if (!isLog(block) || species(block) != kind) continue
                found.add(key)
                // ponytail: bails on huge connected forests; raise maxClusterLogs if real trees hit it
                if (found.size > config.maxClusterLogs) return null
            }
        }
        return found
    }

    private fun isGround(block: Block) = block.solid() && !isLog(block) && !isLeaf(block)

    private fun hasOpenSide(
        instance: Instance,
        pos: Triple<Int, Int, Int>,
    ): Boolean =
        FACE_OFFSETS.any { (dx, dy, dz) ->
            dy == 0 && blockAt(instance, Triple(pos.first + dx, pos.second, pos.third + dz)).let { !it.solid() || isLeaf(it) }
        }

    /**
     * Splits [cluster] into trees. A trunk base is where a log comes out of the ground: open beside it,
     * standing on ground or on a buried log (big trunks sink into the dirt). Touching bases form one trunk,
     * every log joins the trunk reached first through the logs. Returns each log's trunk and each trunk's
     * ground level.
     */
    private fun assignTrunks(
        cluster: List<Triple<Int, Int, Int>>,
        instance: Instance,
    ): Pair<Map<Triple<Int, Int, Int>, Int>, Map<Int, Int>> {
        val logs = HashSet(cluster)

        // A log inside a thick trunk also has no open side, so "buried" needs ground beside it too;
        // otherwise the spot where a thick trunk narrows would count as a base mid-air.
        fun buried(pos: Triple<Int, Int, Int>) =
            pos in logs &&
                !hasOpenSide(instance, pos) &&
                FACE_OFFSETS.any { (dx, dy, dz) ->
                    dy == 0 &&
                        isGround(blockAt(instance, Triple(pos.first + dx, pos.second, pos.third + dz)))
                }
        val bases =
            cluster.filterTo(HashSet()) { (x, y, z) ->
                val below = Triple(x, y - 1, z)
                hasOpenSide(instance, Triple(x, y, z)) && (isGround(blockAt(instance, below)) || buried(below))
            }
        // Floating or cut-off tree: one trunk standing on its lowest log.
        if (bases.isEmpty()) return cluster.associateWith { 0 } to mapOf(0 to cluster.minOf { it.second })

        val owner = HashMap<Triple<Int, Int, Int>, Int>()
        val groundY = HashMap<Int, Int>()
        var trunks = 0
        for (base in bases) {
            if (base in owner) continue
            val id = trunks++
            val stack = ArrayDeque(listOf(base))
            owner[base] = id
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                groundY.merge(id, current.second, ::minOf)
                for ((dx, dy, dz) in ADJACENT_OFFSETS) {
                    val key = Triple(current.first + dx, current.second + dy, current.third + dz)
                    if (key in bases && key !in owner) {
                        owner[key] = id
                        stack.add(key)
                    }
                }
            }
        }
        val queue = ArrayDeque(bases)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for ((dx, dy, dz) in ADJACENT_OFFSETS) {
                val key = Triple(current.first + dx, current.second + dy, current.third + dz)
                if (key in logs && key !in owner) {
                    owner[key] = owner.getValue(current)
                    queue.add(key)
                }
            }
        }
        return owner to groundY
    }

    private fun looksLikeStructure(
        logs: List<Triple<Int, Int, Int>>,
        instance: Instance,
    ): Boolean {
        val set = HashSet(logs)
        // Long straight horizontal beams are buildings, not branches.
        for ((ax, az) in listOf(1 to 0, 0 to 1)) {
            for ((x, y, z) in logs) {
                if (Triple(x - ax, y, z - az) in set) continue
                var length = 1
                while (Triple(x + ax * length, y, z + az * length) in set) length++
                if (length > config.maxHorizontalRun) return true
            }
        }
        var vertical = 0
        var horizontal = 0
        for (pos in logs) {
            when (blockAt(instance, pos).getProperty("axis")) {
                "y" -> vertical++
                "x", "z" -> horizontal++
            }
        }
        return horizontal > 0 && vertical.toDouble() / horizontal < config.minVerticalRatio
    }

    /**
     * Leaves of species [kind] (any species when null) belonging to trunk [tree]. Leaves spread out from
     * every log of [cluster] at once, through leaves only and within [TreeFellerConfig.leafRange] steps;
     * each leaf belongs to the trunk that reaches it first, so neighbouring canopies stay standing.
     */
    private fun collectLeaves(
        logs: List<Triple<Int, Int, Int>>,
        cluster: List<Triple<Int, Int, Int>>,
        owner: Map<Triple<Int, Int, Int>, Int>,
        tree: Int,
        instance: Instance,
        kind: String?,
    ): List<Triple<Int, Int, Int>> {
        val offsets = if (config.diagonalLeaves) ADJACENT_OFFSETS else FACE_OFFSETS
        val cutY = logs[0].second
        val margin = config.leafRange * 2
        val minX = logs.minOf { it.first } - margin
        val maxX = logs.maxOf { it.first } + margin
        val minZ = logs.minOf { it.third } - margin
        val maxZ = logs.maxOf { it.third } + margin
        // Our stump and roots stay, so they don't hold leaves; other trunks only matter near our tree.
        val sources =
            cluster.filter { (x, y, z) ->
                x in minX..maxX && z in minZ..maxZ && (owner[Triple(x, y, z)] != tree || y >= cutY)
            }
        val clusterSet = HashSet(cluster)
        // Pass 1 finds logs of other trees (another species or not touching ours) whose leaves mingle with
        // ours; pass 2 lets them compete for leaves too, so felling one tree never strips a neighbour's bush.
        val foreign = HashSet<Triple<Int, Int, Int>>()
        var found = spreadLeaves(sources, owner, emptySet(), tree, instance, kind, offsets, clusterSet, foreign)
        if (foreign.isNotEmpty()) found = spreadLeaves(sources, owner, foreign, tree, instance, kind, offsets, clusterSet, null)
        return found
    }

    private fun spreadLeaves(
        sources: List<Triple<Int, Int, Int>>,
        owner: Map<Triple<Int, Int, Int>, Int>,
        foreign: Set<Triple<Int, Int, Int>>,
        tree: Int,
        instance: Instance,
        kind: String?,
        offsets: List<Triple<Int, Int, Int>>,
        cluster: Set<Triple<Int, Int, Int>>,
        foreignOut: MutableSet<Triple<Int, Int, Int>>?,
    ): List<Triple<Int, Int, Int>> {
        val claim = HashMap<Triple<Int, Int, Int>, Int>()
        for (pos in sources) claim[pos] = owner.getValue(pos)
        for (pos in foreign) claim[pos] = -1
        val found = mutableListOf<Triple<Int, Int, Int>>()
        var frontier = sources + foreign
        var steps = 0
        while (frontier.isNotEmpty() && steps < config.leafRange && found.size < config.maxLeaves) {
            val next = mutableListOf<Triple<Int, Int, Int>>()
            for (pos in frontier) {
                val (cx, cy, cz) = pos
                for ((dx, dy, dz) in offsets) {
                    val key = Triple(cx + dx, cy + dy, cz + dz)
                    if (key in claim) continue
                    val block = blockAt(instance, key)
                    if (!isLeaf(block) || (kind != null && leafSpecies(block) != kind)) {
                        if (foreignOut != null && isLog(block) && key !in cluster && pos !in cluster) foreignOut.add(key)
                        continue
                    }
                    val id = claim.getValue(pos)
                    claim[key] = id
                    next.add(key)
                    if (id == tree) found.add(key)
                }
            }
            frontier = next
            steps++
        }
        return found
    }

    fun saplingMaterial(logBlock: Block): Material? = saplingsByLogTag.firstOrNull { (tag, _) -> blockIsInTag(logBlock, tag) }?.second

    private fun rollLeafDrop(saplingMaterial: Material?): List<ItemStack> {
        val saplingChance = config.saplingChance
        val stickChance = config.stickChance
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
        val perTick = config.blocksPerTick.coerceAtLeast(1)
        val interval = config.tickInterval.coerceAtLeast(1)

        var index = 0
        var leavesBroken = 0
        val dropped = LinkedHashMap<Material, Int>()
        val base = ordered.first().first
        val dropPos = Pos(base.first + 0.5, base.second + 0.5, base.third + 0.5)
        ModuleScheduler.submitTask {
            var done = 0
            while (done < perTick && index < ordered.size) {
                val (pos, leaf) = ordered[index++]
                done++
                val (x, y, z) = pos
                val fellingPosition = FellingPosition(instance, x, y, z)
                val current = blockAt(instance, pos)
                val isExpected = if (leaf) isLeaf(current) else isLog(current)
                if (!isExpected) {
                    activePositions.remove(fellingPosition)
                    continue
                }
                val stateId = current.stateId()
                instance.setBlock(x, y, z, Block.AIR)
                activePositions.remove(fellingPosition)
                // Each break effect is a sound plus dozens of particles; thousands of leaves at once drop
                // client FPS hard, so only every Nth leaf shows one.
                if (!leaf || leavesBroken++ % config.leafEffectEvery.coerceAtLeast(1) == 0) {
                    instance
                        .getChunk(x shr 4, z shr 4)
                        ?.sendPacketToViewers(
                            WorldEventPacket(2001, BlockVec(x, y, z), stateId, false),
                        )
                }
                val drops =
                    if (leaf) {
                        rollLeafDrop(saplingMaterial)
                    } else {
                        current.registry()?.material()?.let { listOf(ItemStack.of(it)) }
                    }
                drops?.forEach { dropped.merge(it.material(), it.amount(), Int::plus) }
            }
            // Hundreds of loose item entities also cost client FPS, so each tick's drops land merged at the base.
            // Spawned every tick, not at the end, so a module reload mid-fell can't lose them.
            for ((material, total) in dropped) {
                var left = total
                while (left > 0) {
                    val amount = minOf(left, material.maxStackSize())
                    Items.spawn(instance, dropPos, ItemStack.of(material, amount))
                    left -= amount
                }
            }
            dropped.clear()
            if (index < ordered.size) TaskSchedule.tick(interval) else TaskSchedule.stop()
        }
    }

    fun init(config: TreeFellerConfig = TreeFellerConfig()) {
        this.config = config
        if (config.enabled) TreeFellerListener.init()
    }

    fun shutdown() {
        activePositions.clear()
    }
}
