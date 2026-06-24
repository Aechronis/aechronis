package net.aechronis.vanilla.managers

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
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.iterator
import kotlin.math.abs

object Saplings {
    val saplings = ConcurrentHashMap<BlockKey, SaplingsPlanted>()

    fun init() {
        val timeStart = System.currentTimeMillis()

        SaplingsListener.init()

        MinecraftServer
            .getSchedulerManager()
            .buildTask(::growthTick)
            .repeat(TaskSchedule.seconds(Vanilla.config!!.saplingGrowthCheckSeconds))
            .schedule()

        val timeEnd = System.currentTimeMillis()
        println("Saplings enabled in ${timeEnd - timeStart}ms")
    }

    private fun growthTick() {
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<BlockKey>()
        for ((key, planted) in saplings) {
            if (!key.instance.getBlock(key.pos).compare(planted.type.saplingBlock)) {
                toRemove.add(key)
                continue
            }
            if (now - planted.plantedAt < Vanilla.config!!.saplingGrowthMs) continue
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
        if (!hasGiantClearance(instance, corner, type.giantHeight)) return false

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

    private fun hasGiantClearance(
        instance: Instance,
        corner: Vec,
        height: Int,
    ): Boolean {
        for (dx in 0..1) {
            for (dz in 0..1) {
                for (i in 1..height) {
                    val b = instance.getBlock(corner.blockX() + dx, corner.blockY() + i, corner.blockZ() + dz)
                    if (!b.isAir) return false
                }
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

        if (!hasClearance(instance, pos, planted.type.height)) return false

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

    private fun hasClearance(
        instance: Instance,
        pos: Vec,
        height: Int,
    ): Boolean {
        val x = pos.blockX()
        val z = pos.blockZ()
        for (i in 1..height) {
            if (!instance.getBlock(x, pos.blockY() + i, z).isAir) return false
        }
        return true
    }

    private fun isLeaf(block: Block): Boolean = block.name().endsWith("_leaves")

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
}
