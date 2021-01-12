package io.github.openminigameserver.worldedit.platform.adapters

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEditException
import com.sk89q.worldedit.blocks.BaseItemStack
import com.sk89q.worldedit.entity.BaseEntity
import com.sk89q.worldedit.entity.Entity
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
import com.sk89q.worldedit.world.WorldUnloadedException
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockStateHolder
import net.minestom.server.entity.ItemEntity
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.utils.Position
import java.lang.ref.WeakReference


class MinestomWorld(world: Instance) : AbstractWorld() {

    private val worldRef = WeakReference(world)
    val nativeAccess = MinestomWorldNativeAccess(worldRef, getWorld() is InstanceContainer)

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
        return object : Operation {
            override fun resume(run: RunContext?): Operation? {
                nativeAccess.flush()
                return null
            }

            override fun cancel() {
            }

        }
    }

    override fun getBlock(position: BlockVector3): BlockState {
        val stateId = getWorld().getBlockStateId(MinestomAdapter.asBlockPosition(position))
        return BlockStateIdAccess.getBlockStateById(stateId.toInt())!!
    }

    override fun getFullBlock(position: BlockVector3): BaseBlock {
        return getBlock(position).toBaseBlock()
    }

    override fun <B : BlockStateHolder<B>?> setBlock(
        position: BlockVector3?,
        block: B,
        sideEffects: SideEffectSet?
    ): Boolean {
        return nativeAccess.setBlock(position, block, sideEffects)
    }

    override fun getEntities(region: Region?): MutableList<out Entity> {
        TODO("Not yet implemented")
    }

    override fun getEntities(): MutableList<out Entity> {
        TODO("Not yet implemented")
    }

    override fun createEntity(location: Location?, entity: BaseEntity?): Entity? {
        TODO("Not yet implemented")
    }

    override fun getId(): String {
        return getWorld().uniqueId.toString()
    }

    override fun getName(): String {
        return id
    }

    override fun applySideEffects(
        position: BlockVector3?,
        previousType: BlockState?,
        sideEffectSet: SideEffectSet?
    ): MutableSet<SideEffect> = mutableSetOf()

    override fun getBlockLightLevel(position: BlockVector3?): Int {
        return 0
    }

    override fun clearContainerBlockContents(position: BlockVector3?): Boolean = false

    override fun dropItem(position: Vector3, item: BaseItemStack) {
        ItemEntity(MinestomAdapter.toItemStack(item), MinestomAdapter.toPosition(position), getWorld())
    }

    override fun simulateBlockMine(position: BlockVector3?) {
    }

    override fun generateTree(
        type: TreeGenerator.TreeType?,
        editSession: EditSession?,
        position: BlockVector3?
    ): Boolean = false

    override fun getSpawnPosition(): BlockVector3 {
        return MinestomAdapter.asBlockVector(Position(0f, 0f, 0f))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MinestomWorld

        if (worldRef.get()?.uniqueId != other.worldRef.get()?.uniqueId) return false

        return true
    }

    override fun hashCode(): Int {
        return worldRef.get().hashCode()
    }


}