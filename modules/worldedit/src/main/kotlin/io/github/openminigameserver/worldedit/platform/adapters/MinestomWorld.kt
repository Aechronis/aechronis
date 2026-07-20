package io.github.openminigameserver.worldedit.platform.adapters

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEditException
import com.sk89q.worldedit.blocks.BaseItemStack
import com.sk89q.worldedit.entity.BaseEntity
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.function.operation.Operation
import com.sk89q.worldedit.function.operation.RunContext
import com.sk89q.worldedit.internal.block.BlockStateIdAccess
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.math.Vector3
import com.sk89q.worldedit.regions.Region
import com.sk89q.worldedit.util.Location
import com.sk89q.worldedit.util.SideEffect
import com.sk89q.worldedit.util.SideEffectSet
import com.sk89q.worldedit.util.TreeGenerator
import com.sk89q.worldedit.world.AbstractWorld
import com.sk89q.worldedit.world.World
import com.sk89q.worldedit.world.WorldUnloadedException
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockStateHolder
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.ItemEntity
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import com.sk89q.worldedit.entity.Entity as WorldEditEntity

private class MinestomEntityAdapter(
    private val entity: net.minestom.server.entity.Entity,
    private val world: World,
) : WorldEditEntity {
    override fun getLocation(): Location = MinestomAdapter.asLocation(world, entity.position)

    override fun setLocation(location: Location): Boolean {
        entity.teleport(MinestomAdapter.toPosition(location))
        return true
    }

    override fun getExtent(): Extent = world

    override fun <T : Any?> getFacet(cls: Class<out T>?): T? = null

    override fun getState(): BaseEntity? {
        TODO("Not yet implemented")
    }

    override fun remove(): Boolean = entity.remove().let { true }
}

class MinestomWorld(
    world: Instance,
) : AbstractWorld() {
    private val worldRef = WeakReference(world)
    val nativeAccess = MinestomWorldNativeAccess(worldRef, getWorld() is InstanceContainer)
    private val loadedChunks = ConcurrentHashMap.newKeySet<Long>()

    fun setActor(actor: Actor?) {
        nativeAccess.actor = actor
    }

    /**
     * Get the underlying handle to the world.
     *
     * @return the world
     * @throws WorldEditException thrown if a reference to the world was lost (i.e. world was unloaded)
     */
    @Throws(WorldEditException::class)
    fun getWorldChecked(): Instance {
        val world: Instance? = worldRef.get()
        return world ?: throw WorldUnloadedException()
    }

    /**
     * Get the underlying handle to the world.
     *
     * @return the world
     * @throws RuntimeException thrown if a reference to the world was lost (i.e. world was unloaded)
     */
    fun getWorld(): Instance {
        val world = worldRef.get()
        return world
            ?: throw RuntimeException("The reference to the world was lost (i.e. the world may have been unloaded)")
    }

    override fun commit(): Operation {
        var flushCompletion: CompletableFuture<Unit>? = null
        return object : Operation {
            override fun resume(run: RunContext?): Operation? {
                val completion = flushCompletion ?: nativeAccess.flush().also { flushCompletion = it }
                completion.join()
                return null
            }

            override fun cancel() {
            }
        }
    }

    override fun checkLoadedChunk(pt: BlockVector3) {
        val chunkX = Math.floorDiv(pt.x(), 16)
        val chunkZ = Math.floorDiv(pt.z(), 16)
        val chunkKey = (chunkX.toLong() shl 32) xor (chunkZ.toLong() and 0xffffffffL)
        if (loadedChunks.contains(chunkKey)) return

        val world = getWorld()
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            world.loadChunk(chunkX, chunkZ).join()
        }
        loadedChunks.add(chunkKey)
    }

    override fun getBlock(position: BlockVector3): BlockState {
        checkLoadedChunk(position)
        val block = getWorld().getBlock(position.x(), position.y(), position.z())
        return BlockStateIdAccess.getBlockStateById(block.stateId())!!
    }

    override fun getFullBlock(position: BlockVector3): BaseBlock = getBlock(position).toBaseBlock()

    override fun <B : BlockStateHolder<B>?> setBlock(
        position: BlockVector3?,
        block: B,
        sideEffects: SideEffectSet?,
    ): Boolean {
        position?.let { checkLoadedChunk(it) }
        return nativeAccess.setBlock(position, block, sideEffects)
    }

    override fun getEntities(region: Region?): MutableList<out WorldEditEntity> {
        TODO("Not yet implemented")
    }

    override fun getEntities(): MutableList<out WorldEditEntity> =
        getWorld().entities.map { MinestomEntityAdapter(it, this) }.toMutableList()

    override fun createEntity(
        location: Location?,
        entity: BaseEntity?,
    ): WorldEditEntity? {
        TODO("Not yet implemented")
    }

    override fun id(): String = getWorld().uuid.toString()

    override fun getName(): String = id()

    override fun applySideEffects(
        position: BlockVector3?,
        previousType: BlockState?,
        sideEffectSet: SideEffectSet?,
    ): MutableSet<SideEffect> = mutableSetOf()

    override fun getBlockLightLevel(position: BlockVector3?): Int = 0

    override fun clearContainerBlockContents(position: BlockVector3?): Boolean = false

    override fun dropItem(
        position: Vector3,
        item: BaseItemStack,
    ) {
        ItemEntity(MinestomAdapter.toItemStack(item)).setInstance(getWorld(), MinestomAdapter.toPosition(position))
    }

    override fun simulateBlockMine(position: BlockVector3?) {
    }

    override fun generateTree(
        type: TreeGenerator.TreeType?,
        editSession: EditSession?,
        position: BlockVector3?,
    ): Boolean = false

    override fun generateTree(
        type: com.sk89q.worldedit.world.generation.TreeType?,
        editSession: EditSession?,
        position: BlockVector3?,
    ): Boolean = false

    override fun getSpawnPosition(): BlockVector3 = MinestomAdapter.asBlockVector(Pos(0.0, 0.0, 0.0))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MinestomWorld

        if (worldRef.get()?.uuid != other.worldRef.get()?.uuid) return false

        return true
    }

    override fun hashCode(): Int = worldRef.get().hashCode()
}
