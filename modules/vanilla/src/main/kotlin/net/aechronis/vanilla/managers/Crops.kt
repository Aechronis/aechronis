package net.aechronis.vanilla.managers

import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.CropsPlantListener
import net.aechronis.vanilla.objects.BlockKey
import net.aechronis.vanilla.objects.CropType
import net.aechronis.vanilla.objects.CropsPlantedCrop
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Vec
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.timer.TaskSchedule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.iterator

object Crops {
    val crops = ConcurrentHashMap<BlockKey, CropsPlantedCrop>()
    val msPerState = mutableMapOf<CropType, Long>()

    fun init() {
        val timeStart = System.currentTimeMillis()
        msPerState[CropType.Wheat] = Vanilla.config.wheatMsPerStage
        msPerState[CropType.Carrots] = Vanilla.config.carrotMsPerStage
        msPerState[CropType.Potatoes] = Vanilla.config.potatoMsPerStage

        CropsPlantListener.init()

        ModuleScheduler
            .buildTask(::growthTick)
            .repeat(TaskSchedule.seconds(Vanilla.config.cropGrowthCheckSeconds))
            .schedule()
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("├─ Crops enabled in ${timeLoad}ms")
    }

    internal fun captureTransientState(): ByteArray {
        val entries = crops.entries.toList()
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(TRANSIENT_STATE_VERSION)
                output.writeInt(entries.size)
                entries.forEach { (key, planted) ->
                    output.writeUTF(key.instance.getDimensionName())
                    output.writeInt(key.pos.blockX())
                    output.writeInt(key.pos.blockY())
                    output.writeInt(key.pos.blockZ())
                    output.writeUTF(planted.cropType.cropBlock.name())
                    output.writeLong(planted.plantedAt)
                    output.writeInt(planted.initialAge)
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
            val restored = HashMap<BlockKey, CropsPlantedCrop>()
            records.forEach { record ->
                val instance = instances[record.world] ?: return@forEach
                val type = CropType.ALL.firstOrNull { it.cropBlock.name() == record.cropBlock } ?: return@forEach
                val key = BlockKey(instance, Vec(record.x.toDouble(), record.y.toDouble(), record.z.toDouble()))
                if (!instance.getBlock(key.pos).compare(type.cropBlock)) return@forEach
                restored[key] = CropsPlantedCrop(type, record.plantedAt, record.initialAge)
            }
            crops.clear()
            crops.putAll(restored)
        } catch (error: Throwable) {
            System.err.println("Failed to restore crop growth state: ${error.message}")
            throw error
        }
    }

    fun shutdown() {
        crops.clear()
        msPerState.clear()
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

    private fun decodeTransientState(payload: ByteArray): List<CropState> =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == TRANSIENT_STATE_VERSION) { "Unsupported crop growth state version" }
            val size = input.readInt()
            require(size in 0..MAX_TRANSIENT_ENTRIES) { "Invalid crop growth state size: $size" }
            List(size) {
                CropState(
                    world = input.readUTF(),
                    x = input.readInt(),
                    y = input.readInt(),
                    z = input.readInt(),
                    cropBlock = input.readUTF(),
                    plantedAt = input.readLong(),
                    initialAge = input.readInt(),
                )
            }
        }

    private data class CropState(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val cropBlock: String,
        val plantedAt: Long,
        val initialAge: Int,
    )

    private const val TRANSIENT_STATE_VERSION = 1
    private const val MAX_TRANSIENT_ENTRIES = 1_000_000
}
