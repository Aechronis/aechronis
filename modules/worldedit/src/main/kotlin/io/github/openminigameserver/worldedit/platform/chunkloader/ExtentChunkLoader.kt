package io.github.openminigameserver.worldedit.platform.chunkloader

import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.internal.block.BlockStateIdAccess
import com.sk89q.worldedit.math.BlockVector3
import net.minestom.server.instance.*
import net.minestom.server.utils.chunk.ChunkCallback
import net.minestom.server.world.biomes.Biome
import java.util.*

class ExtentChunkLoader(val extent: Extent) : IChunkLoader {

    override fun loadChunk(instance: Instance, chunkX: Int, chunkZ: Int, callback: ChunkCallback?): Boolean {
        val chunk =
            if (instance is InstanceContainer) instance.chunkSupplier.createChunk(arrayOf(Biome.PLAINS), chunkX, chunkZ)
            else DynamicChunk(arrayOf(Biome.PLAINS), chunkX, chunkZ)
        Arrays.fill(chunk.biomes, Biome.PLAINS)

        for (z in 0 until Chunk.CHUNK_SIZE_Z) {
            for (x in 0 until Chunk.CHUNK_SIZE_X) {
                for (y in 0 until Chunk.CHUNK_SIZE_Y) {
                    val actualX = chunkX * 16 + x
                    val actualZ = chunkZ * 16 + z

                    val extentBlock = extent.getBlock(BlockVector3.at(actualX, y, actualZ))
                    chunk.UNSAFE_setBlock(
                        x,
                        y,
                        z,
                        BlockStateIdAccess.getBlockStateId(extentBlock).toShort(),
                        0,
                        null,
                        false
                    )
                }
            }
        }


        callback?.accept(chunk)
        return true
    }

    override fun saveChunk(chunk: Chunk, callback: Runnable?) {
        for (z in 0 until Chunk.CHUNK_SIZE_Z) {
            for (x in 0 until Chunk.CHUNK_SIZE_X) {
                for (y in 0 until Chunk.CHUNK_SIZE_Y) {
                    val actualX = chunk.chunkX * 16 + x
                    val actualZ = chunk.chunkZ * 16 + z

                    val blockState = chunk.getBlockStateId(
                        x,
                        y,
                        z
                    )
                    extent.setBlock(
                        BlockVector3.at(actualX, y, actualZ),
                        BlockStateIdAccess.getBlockStateById(blockState.toInt())
                    )
                }
            }
        }
        callback?.run()
    }

}