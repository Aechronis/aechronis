package net.aechronis.vanilla.managers

import net.aechronis.server.modules.ModuleBlocks
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.SaplingsListener
import net.aechronis.vanilla.objects.BlockKey
import net.aechronis.vanilla.objects.SaplingType
import net.aechronis.vanilla.objects.SaplingsPlanted
import net.aechronis.vanilla.objects.TreeBuilder
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Vec
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockTags
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.timer.TaskSchedule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.iterator
import kotlin.math.abs

object Saplings {
    val saplings = ConcurrentHashMap<BlockKey, SaplingsPlanted>()

    fun init() {
        val timeStart = System.currentTimeMillis()

        registerPlacementRules()
        SaplingsListener.init()

        ModuleScheduler
            .buildTask(::growthTick)
            .repeat(TaskSchedule.seconds(Vanilla.config.saplingGrowthCheckSeconds))
            .schedule()

        val timeEnd = System.currentTimeMillis()
        println("├─ Saplings enabled in ${timeEnd - timeStart}ms")
    }

    internal fun captureTransientState(): ByteArray {
        val entries = saplings.entries.toList()
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(TRANSIENT_STATE_VERSION)
                output.writeInt(entries.size)
                entries.forEach { (key, planted) ->
                    output.writeUTF(key.instance.getDimensionName())
                    output.writeInt(key.pos.blockX())
                    output.writeInt(key.pos.blockY())
                    output.writeInt(key.pos.blockZ())
                    output.writeUTF(planted.type.saplingBlock.name())
                    output.writeLong(planted.plantedAt)
                    output.writeInt(planted.boneMeal)
                }
            }
            bytes.toByteArray()
        }
    }

    internal fun restoreTransientState(payload: ByteArray?) {
        if (payload == null) return
        try {
            val records = decodeTransientState(payload)
            val instances = MinecraftServer.getInstanceManager().instances.associateBy { it.getDimensionName() }
            val restored = HashMap<BlockKey, SaplingsPlanted>()
            records.forEach { record ->
                val instance = instances[record.world] ?: return@forEach
                val type = SaplingType.ALL.firstOrNull { it.saplingBlock.name() == record.saplingBlock } ?: return@forEach
                val key = BlockKey(instance, Vec(record.x.toDouble(), record.y.toDouble(), record.z.toDouble()))
                if (!instance.getBlock(key.pos).compare(type.saplingBlock)) return@forEach
                restored[key] = SaplingsPlanted(type, record.plantedAt, record.boneMeal)
            }
            saplings.clear()
            saplings.putAll(restored)
        } catch (error: Throwable) {
            System.err.println("Failed to restore sapling growth state: ${error.message}")
            throw error
        }
    }

    fun shutdown() {
        saplings.clear()
    }

    internal fun registerPlacementRules() {
        val registry = MinecraftServer.process().blocks()
        val saplingTag = requireNotNull(registry.getTag(BlockTags.SAPLINGS))
        val blockManager = MinecraftServer.getBlockManager()
        for (key in saplingTag) {
            val block = requireNotNull(registry.get(key))
            val existing = blockManager.getBlockPlacementRule(block)
            if (existing is SaplingPlacementRule) continue
            ModuleBlocks.registerPlacementRule(block) { originalFallback ->
                SaplingPlacementRule(block, originalFallback)
            }
        }
    }

    private fun decodeTransientState(payload: ByteArray): List<SaplingState> =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == TRANSIENT_STATE_VERSION) { "Unsupported sapling growth state version" }
            val size = input.readInt()
            require(size in 0..MAX_TRANSIENT_ENTRIES) { "Invalid sapling growth state size: $size" }
            List(size) {
                SaplingState(
                    world = input.readUTF(),
                    x = input.readInt(),
                    y = input.readInt(),
                    z = input.readInt(),
                    saplingBlock = input.readUTF(),
                    plantedAt = input.readLong(),
                    boneMeal = input.readInt(),
                )
            }
        }

    private data class SaplingState(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val saplingBlock: String,
        val plantedAt: Long,
        val boneMeal: Int,
    )

    private fun growthTick() {
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<BlockKey>()
        for ((key, planted) in saplings) {
            if (!key.instance.getBlock(key.pos).compare(planted.type.saplingBlock)) {
                toRemove.add(key)
                continue
            }
            if (now - planted.plantedAt < Vanilla.config.saplingGrowthMs) continue
            if (planted.type.giant && tryGiant(key.instance, key.pos, planted.type)) {
                toRemove.add(key)
                continue
            }
            if (grow(key, planted)) toRemove.add(key)
        }
        toRemove.forEach { saplings.remove(it) }
    }

    fun tryGiant(
        instance: Instance,
        pos: Vec,
        type: SaplingType,
    ): Boolean {
        if (!type.giant) return false
        val corner = findGiantCorner(instance, pos, type) ?: return false
        return growGiant(instance, corner, type)
    }

    private fun findGiantCorner(
        instance: Instance,
        pos: Vec,
        type: SaplingType,
    ): Vec? {
        for (ox in -1..0) {
            for (oz in -1..0) {
                val corner = pos.add(ox.toDouble(), 0.0, oz.toDouble())
                if (isSaplingSquare(instance, corner, type)) return corner
            }
        }
        return null
    }

    private fun isSaplingSquare(
        instance: Instance,
        corner: Vec,
        type: SaplingType,
    ): Boolean {
        for (dx in 0..1) {
            for (dz in 0..1) {
                val b = instance.getBlock(corner.blockX() + dx, corner.blockY(), corner.blockZ() + dz)
                if (!b.compare(type.saplingBlock)) return false
            }
        }
        return true
    }

    private fun growGiant(
        instance: Instance,
        corner: Vec,
        type: SaplingType,
    ): Boolean {
        if (!hasLogClearance(instance, corner, type, giant = true)) return false

        val builder =
            BlockTreeBuilder(
                instance,
                corner.blockX(),
                corner.blockY(),
                corner.blockZ(),
                type.logBlock,
                type.leavesBlock,
            )
        type.buildGiant(builder)

        for (dx in 0..1) {
            for (dz in 0..1) {
                saplings.remove(BlockKey(instance, corner.add(dx.toDouble(), 0.0, dz.toDouble())))
            }
        }
        return true
    }

    fun grow(
        key: BlockKey,
        planted: SaplingsPlanted,
    ): Boolean {
        val instance = key.instance
        val pos = key.pos
        if (!instance.getBlock(pos).compare(planted.type.saplingBlock)) return true

        if (!hasLogClearance(instance, pos, planted.type, giant = false)) return false

        val builder =
            BlockTreeBuilder(
                instance,
                pos.blockX(),
                pos.blockY(),
                pos.blockZ(),
                planted.type.logBlock,
                planted.type.leavesBlock,
            )
        planted.type.build(builder)

        return true
    }

    private fun hasLogClearance(
        instance: Instance,
        base: Vec,
        type: SaplingType,
        giant: Boolean,
    ): Boolean {
        val checker =
            LogClearanceTreeBuilder { dx, dy, dz ->
                val block = instance.getBlock(base.blockX() + dx, base.blockY() + dy, base.blockZ() + dz)
                val replacesSourceSapling =
                    dy == 0 &&
                        if (giant) {
                            dx in 0..1 && dz in 0..1 && block.compare(type.saplingBlock)
                        } else {
                            dx == 0 && dz == 0 && block.compare(type.saplingBlock)
                        }
                block.isAir || replacesSourceSapling
            }

        if (giant) {
            type.buildGiant(checker)
        } else {
            type.build(checker)
        }
        return checker.hasClearance
    }

    private fun isLeaf(block: Block): Boolean = block.name().endsWith("_leaves")

    private class LogClearanceTreeBuilder(
        private val isClear: (Int, Int, Int) -> Boolean,
    ) : TreeBuilder {
        var hasClearance = true
            private set

        override fun log(
            dx: Int,
            dy: Int,
            dz: Int,
        ) {
            if (!isClear(dx, dy, dz)) hasClearance = false
        }

        override fun leaf(
            dx: Int,
            dy: Int,
            dz: Int,
        ) = Unit

        override fun leafLayer(
            dy: Int,
            radius: Int,
            trimCorners: Boolean,
        ) = Unit
    }

    private class BlockTreeBuilder(
        private val instance: Instance,
        private val baseX: Int,
        private val baseY: Int,
        private val baseZ: Int,
        private val logBlock: Block,
        private val leavesBlock: Block,
    ) : TreeBuilder {
        override fun log(
            dx: Int,
            dy: Int,
            dz: Int,
        ) = set(baseX + dx, baseY + dy, baseZ + dz, logBlock, overwriteSolid = true)

        override fun leaf(
            dx: Int,
            dy: Int,
            dz: Int,
        ) = set(baseX + dx, baseY + dy, baseZ + dz, leavesBlock, overwriteSolid = false)

        override fun leafLayer(
            dy: Int,
            radius: Int,
            trimCorners: Boolean,
        ) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    if (dx == 0 && dz == 0) continue // leave the trunk column
                    if (trimCorners && abs(dx) == radius && abs(dz) == radius) continue
                    leaf(dx, dy, dz)
                }
            }
        }

        private fun set(
            x: Int,
            y: Int,
            z: Int,
            block: Block,
            overwriteSolid: Boolean,
        ) {
            val current = instance.getBlock(x, y, z)
            if (!overwriteSolid && !current.isAir && !isLeaf(current)) return
            instance.setBlock(x, y, z, block)
            val chunk = instance.getChunk(x shr 4, z shr 4) ?: return
            chunk.sendPacketToViewers(BlockChangePacket(BlockVec(x, y, z), block.stateId()))
        }
    }

    private const val TRANSIENT_STATE_VERSION = 1
    private const val MAX_TRANSIENT_ENTRIES = 1_000_000
}
