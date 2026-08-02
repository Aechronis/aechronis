package io.github.openminigameserver.worldedit.platform.chunkloader

import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.internal.block.BlockStateIdAccess
import com.sk89q.worldedit.math.BlockVector3
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.ChunkLoader
import net.minestom.server.instance.DynamicChunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block

class ExtentChunkLoader(
    val extent: Extent,
) : ChunkLoader {
    override fun loadChunk(
        instance: Instance,
        chunkX: Int,
        chunkZ: Int,
    ): Chunk {
        val chunk =
            if (instance is InstanceContainer) {
                instance.chunkSupplier.createChunk(instance, chunkX, chunkZ)
            } else {
                DynamicChunk(instance, chunkX, chunkZ)
            }

        val dimensionType = instance.cachedDimensionType
        val minY = dimensionType.minY()
        val maxY = minY + dimensionType.height()

        for (z in 0 until Chunk.CHUNK_SIZE_Z) {
            for (x in 0 until Chunk.CHUNK_SIZE_X) {
                for (y in minY until maxY) {
                    val actualX = chunkX * 16 + x
                    val actualZ = chunkZ * 16 + z

                    val extentBlock = extent.getBlock(BlockVector3.at(actualX, y, actualZ))
                    val stateId = BlockStateIdAccess.getBlockStateId(extentBlock)
                    chunk.setBlock(x, y, z, Block.fromStateId(stateId))
                }
            }
        }

        return chunk
    }

    override fun saveChunk(chunk: Chunk) {
        val instance = chunk.instance
        val dimensionType = instance.getCachedDimensionType()
        val minY = dimensionType.minY()
        val maxY = minY + dimensionType.height()

        for (z in 0 until Chunk.CHUNK_SIZE_Z) {
            for (x in 0 until Chunk.CHUNK_SIZE_X) {
                for (y in minY until maxY) {
                    val actualX = chunk.chunkX * 16 + x
                    val actualZ = chunk.chunkZ * 16 + z

                    val block = chunk.getBlock(x, y, z)
                    extent.setBlock(
                        BlockVector3.at(actualX, y, actualZ),
                        BlockStateIdAccess.getBlockStateById(block.stateId()),
                    )
                }
            }
        }
    }
}
