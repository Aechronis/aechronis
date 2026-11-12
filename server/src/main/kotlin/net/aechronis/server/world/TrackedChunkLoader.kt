package net.aechronis.server.world

import net.minestom.server.instance.Chunk
import net.minestom.server.instance.ChunkLoader
import net.minestom.server.instance.Instance
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.concurrent.withLock

/**
 * Wraps the primary world's Anvil loader so that only modified chunks are written, a failed write
 * is never mistaken for a saved chunk, and unloading cannot close a region file that is in use.
 */
class TrackedChunkLoader(
    private val delegate: ChunkLoader,
    private val regionDirectory: Path,
) : ChunkLoader {
    private class RegionUse {
        var operations = 0
        val deferredUnloads = ArrayList<Chunk>()
    }

    // Regions with chunk I/O in flight. The Anvil loader closes a region file as its last loaded
    // chunk unloads, even while another thread is still reading or writing that file.
    private val regionsInUse = HashMap<Long, RegionUse>()

    override fun loadInstance(instance: Instance) = delegate.loadInstance(instance)

    override fun saveInstance(instance: Instance) = delegate.saveInstance(instance)

    override fun supportsParallelLoading(): Boolean = delegate.supportsParallelLoading()

    override fun supportsParallelSaving(): Boolean = delegate.supportsParallelSaving()

    override fun loadChunk(
        instance: Instance,
        chunkX: Int,
        chunkZ: Int,
    ): Chunk? {
        val chunk = usingRegion(chunkX, chunkZ) { delegate.loadChunk(instance, chunkX, chunkZ) }
        // Filling a chunk from disk goes through the same setters as a later edit.
        (chunk as? TrackedChunk)?.markLoaded()
        return chunk
    }

    override fun saveChunk(chunk: Chunk) {
        if (chunk !is TrackedChunk) {
            usingRegion(chunk.chunkX, chunk.chunkZ) { delegate.saveChunk(chunk) }
            return
        }
        chunk.saveLock.withLock {
            // An unloaded chunk is no longer the world's copy; a fresh load may have replaced it.
            if (!chunk.isLoaded || !chunk.hasUnsavedChanges) return
            val tags = chunk.beginSave()
            try {
                usingRegion(chunk.chunkX, chunk.chunkZ) {
                    val previous = savedLocation(chunk.chunkX, chunk.chunkZ)
                    delegate.saveChunk(chunk)
                    // The Anvil loader only logs a failed write, and a chunk wrongly taken for
                    // saved would later be unloaded along with its changes. Every successful
                    // write moves the chunk to newly allocated sectors.
                    if (savedLocation(chunk.chunkX, chunk.chunkZ) == previous) {
                        throw IOException("Chunk ${chunk.chunkX}, ${chunk.chunkZ} was not written to its region file")
                    }
                }
            } catch (error: Throwable) {
                chunk.saveFailed()
                throw error
            }
            chunk.saveSucceeded(tags)
        }
    }

    override fun unloadChunk(chunk: Chunk) {
        if (chunk is TrackedChunk && chunk.needsSave) {
            System.err.println("[World] Chunk ${chunk.chunkX}, ${chunk.chunkZ} was unloaded with unsaved changes.")
        }
        synchronized(regionsInUse) {
            val region = regionsInUse[regionIndex(chunk.chunkX, chunk.chunkZ)]
            if (region == null) delegate.unloadChunk(chunk) else region.deferredUnloads += chunk
        }
    }

    private inline fun <T> usingRegion(
        chunkX: Int,
        chunkZ: Int,
        operation: () -> T,
    ): T {
        val index = regionIndex(chunkX, chunkZ)
        synchronized(regionsInUse) { regionsInUse.getOrPut(index, ::RegionUse).operations++ }
        try {
            return operation()
        } finally {
            synchronized(regionsInUse) {
                val region = regionsInUse.getValue(index)
                if (--region.operations == 0) {
                    regionsInUse.remove(index)
                    region.deferredUnloads.forEach(delegate::unloadChunk)
                }
            }
        }
    }

    /** The chunk's entry in its region file's location table, or 0 when it has never been saved. */
    private fun savedLocation(
        chunkX: Int,
        chunkZ: Int,
    ): Int {
        val regionFile = regionDirectory.resolve("r.${chunkX shr 5}.${chunkZ shr 5}.mca")
        val entry = ByteBuffer.allocate(Int.SIZE_BYTES)
        try {
            FileChannel.open(regionFile, StandardOpenOption.READ).use { channel ->
                val position = (((chunkZ and 31) shl 5) or (chunkX and 31)) * Int.SIZE_BYTES.toLong()
                while (entry.hasRemaining()) {
                    if (channel.read(entry, position + entry.position()) < 0) return 0
                }
            }
        } catch (_: NoSuchFileException) {
            return 0
        }
        return entry.getInt(0)
    }

    private fun regionIndex(
        chunkX: Int,
        chunkZ: Int,
    ): Long = ((chunkX shr 5).toLong() shl 32) or ((chunkZ shr 5).toLong() and 0xFFFFFFFFL)
}
