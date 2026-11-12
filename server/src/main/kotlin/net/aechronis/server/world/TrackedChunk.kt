package net.aechronis.server.world

import net.kyori.adventure.nbt.CompoundBinaryTag
import net.minestom.server.instance.DynamicChunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockHandler
import net.minestom.server.registry.RegistryKey
import net.minestom.server.world.biome.Biome
import java.util.concurrent.locks.ReentrantLock

/** A primary-world chunk that knows whether it still matches its copy on disk. */
class TrackedChunk(
    instance: Instance,
    chunkX: Int,
    chunkZ: Int,
) : DynamicChunk(instance, chunkX, chunkZ) {
    // Orders saves of this chunk, so an older capture can never be written over a newer one.
    internal val saveLock = ReentrantLock()

    @Volatile
    private var modified = false

    // Chunk tags are persisted too, but can change without passing through this class.
    @Volatile
    private var savedTags: CompoundBinaryTag = tagHandler().asCompound()

    // Owned by ChunkUnloader: consecutive scans in which nothing needed this chunk.
    internal var idleScans = 0
    internal var queuedForUnload = false

    internal val hasUnsavedChanges: Boolean
        get() = modified || tagHandler().asCompound() != savedTags

    /** True while this chunk differs from disk, including while a save of it is still running. */
    val needsSave: Boolean
        get() = hasUnsavedChanges || saveLock.isLocked

    override fun setBlock(
        x: Int,
        y: Int,
        z: Int,
        block: Block,
        placement: BlockHandler.Placement?,
        destroy: BlockHandler.Destroy?,
    ) {
        modified = true
        super.setBlock(x, y, z, block, placement, destroy)
    }

    override fun setBiome(
        x: Int,
        y: Int,
        z: Int,
        biome: RegistryKey<Biome>,
    ) {
        modified = true
        super.setBiome(x, y, z, biome)
    }

    override fun reset() {
        modified = true
        super.reset()
    }

    // Callers that write sections directly, such as generation forks, invalidate afterwards.
    override fun invalidate() {
        modified = true
        super.invalidate()
    }

    /** Called once a freshly loaded chunk holds exactly what its region file does. */
    internal fun markLoaded() {
        modified = false
        savedTags = tagHandler().asCompound()
    }

    /**
     * Clears the modified flag before the chunk is captured, so a change made while the save runs
     * leaves the chunk modified again. Returns the tags to pass to [saveSucceeded].
     */
    internal fun beginSave(): CompoundBinaryTag {
        val tags = tagHandler().asCompound()
        modified = false
        return tags
    }

    internal fun saveSucceeded(tags: CompoundBinaryTag) {
        savedTags = tags
    }

    internal fun saveFailed() {
        modified = true
    }
}
