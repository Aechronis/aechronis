package net.aechronis.spark

import me.lucko.spark.common.platform.world.AbstractChunkInfo
import me.lucko.spark.common.platform.world.CountMap
import me.lucko.spark.common.platform.world.WorldInfoProvider
import net.minestom.server.MinecraftServer
import java.util.concurrent.atomic.AtomicInteger

/** Spark calls these on the tick thread; all exported collections contain copied values. */
internal class MinestomWorldInfo : WorldInfoProvider {
    override fun mustCallSync(): Boolean = true

    override fun pollCounts(): WorldInfoProvider.CountsResult {
        val instances = MinecraftServer.getInstanceManager().instances.toList()
        return WorldInfoProvider.CountsResult(
            MinecraftServer.getConnectionManager().onlinePlayers.size,
            instances.sumOf { it.entities.size },
            -1, // Minestom has no public, cheap block-entity count; do not report a fabricated zero.
            instances.sumOf { it.chunks.size },
        )
    }

    override fun pollChunks(): WorldInfoProvider.ChunksResult<ChunkCounts> {
        val result = WorldInfoProvider.ChunksResult<ChunkCounts>()
        MinecraftServer.getInstanceManager().instances.toList().forEach { instance ->
            val chunks = instance.chunks.toList().associate { (it.chunkX to it.chunkZ) to ChunkCounts(it.chunkX, it.chunkZ) }
            instance.entities.toList().forEach { entity ->
                val position = entity.position
                chunks[position.chunkX() to position.chunkZ()]?.counts?.increment(entity.entityType.key().asString())
            }
            result.put("${instance.dimensionName} (${instance.uuid})", chunks.values.toList())
        }
        return result
    }

    override fun pollGameRules(): WorldInfoProvider.GameRulesResult = WorldInfoProvider.GameRulesResult()

    override fun pollDataPacks(): Collection<WorldInfoProvider.DataPackInfo> = emptyList()

    class ChunkCounts(
        x: Int,
        z: Int,
    ) : AbstractChunkInfo<String>(x, z) {
        val counts: CountMap<String> = CountMap.Simple(linkedMapOf<String, AtomicInteger>())

        override fun getEntityCounts(): CountMap<String> = counts

        override fun entityTypeName(type: String): String = type
    }
}
