package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.VanillaConfig
import net.aechronis.vanilla.listeners.CropsBreakListener
import net.aechronis.vanilla.listeners.CropsPlantListener
import net.aechronis.vanilla.objects.BlockKey
import net.aechronis.vanilla.objects.CropType
import net.aechronis.vanilla.objects.CropsPlantedCrop
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.iterator

object Crops {
    val crops = ConcurrentHashMap<BlockKey, CropsPlantedCrop>()
    val msPerState = mutableMapOf<CropType, Long>()

    fun init() {
        val timeStart = System.currentTimeMillis()
        msPerState[CropType.Wheat] = Vanilla.config!!.wheatMsPerStage
        msPerState[CropType.Carrots] = Vanilla.config!!.carrotMsPerStage
        msPerState[CropType.Potatoes] = Vanilla.config!!.potatoMsPerStage

        CropsPlantListener.init()
        CropsBreakListener.init()

        MinecraftServer
            .getSchedulerManager()
            .buildTask(::growthTick)
            .repeat(TaskSchedule.seconds(Vanilla.config!!.cropGrowthCheckSeconds))
            .schedule()
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Crops enabled in ${timeLoad}ms")
    }

    private fun growthTick() {
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<BlockKey>()
        for ((key, planted) in crops) {
            val mps = msPerState[planted.cropType] ?: continue
            val targetAge =
                (planted.initialAge + ((now - planted.plantedAt) / mps).toInt())
                    .coerceAtMost(planted.cropType.maxAge)
            val currentBlock = key.instance.getBlock(key.pos)
            if (!currentBlock.compare(planted.cropType.cropBlock)) {
                toRemove.add(key)
                continue
            }
            val currentAge = currentBlock.getProperty("age")?.toIntOrNull() ?: 0
            if (targetAge <= currentAge) continue
            val newBlock = planted.cropType.cropBlock.withProperty("age", targetAge.toString())
            key.instance.setBlock(key.pos, newBlock)
            val blockPos = BlockVec(key.pos.x().toInt(), key.pos.y().toInt(), key.pos.z().toInt())
            val chunk = key.instance.getChunkAt(key.pos)
            chunk?.sendPacketToViewers(BlockChangePacket(blockPos, newBlock.stateId()))
            if (targetAge == planted.cropType.maxAge) {
                toRemove.add(key)
            }
        }
        toRemove.forEach { crops.remove(it) }
    }
}
