package io.github.openminigameserver.worldedit.platform.adapters

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEditException
import com.sk89q.worldedit.blocks.BaseItemStack
import com.sk89q.worldedit.entity.BaseEntity
import com.sk89q.worldedit.entity.metadata.EntityProperties
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
import com.sk89q.worldedit.util.concurrency.LazyReference
import com.sk89q.worldedit.world.AbstractWorld
import com.sk89q.worldedit.world.World
import com.sk89q.worldedit.world.WorldUnloadedException
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockStateHolder
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.ItemEntity
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.ambient.AmbientCreatureMeta
import net.minestom.server.entity.metadata.animal.AnimalMeta
import net.minestom.server.entity.metadata.animal.tameable.TameableAnimalMeta
import net.minestom.server.entity.metadata.golem.AbstractGolemMeta
import net.minestom.server.entity.metadata.other.ArmorStandMeta
import net.minestom.server.entity.metadata.villager.AbstractVillagerMeta
import net.minestom.server.entity.metadata.water.WaterAnimalMeta
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.item.ItemStack
import net.minestom.server.item.component.CustomData
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import com.sk89q.worldedit.entity.Entity as WorldEditEntity
import com.sk89q.worldedit.world.entity.EntityType as WorldEditEntityType
import net.minestom.server.entity.EntityType as MinestomEntityType

private class MinestomEntityProperties(
    private val entity: Entity,
) : EntityProperties {
    private val type = entity.entityType
    private val typeId = type.key().asString()
    private val meta = entity.entityMeta

    override fun isPlayerDerived(): Boolean = entity is Player || type == MinestomEntityType.PLAYER

    override fun isProjectile(): Boolean = typeId in projectileTypes

    override fun isItem(): Boolean = entity is ItemEntity || type == MinestomEntityType.ITEM

    override fun isFallingBlock(): Boolean = type == MinestomEntityType.FALLING_BLOCK

    override fun isPainting(): Boolean = type == MinestomEntityType.PAINTING

    override fun isItemFrame(): Boolean = type == MinestomEntityType.ITEM_FRAME || type == MinestomEntityType.GLOW_ITEM_FRAME

    override fun isBoat(): Boolean = typeId.endsWith("_boat") || typeId.endsWith("_raft")

    override fun isMinecart(): Boolean = typeId.endsWith("minecart")

    override fun isTNT(): Boolean = type == MinestomEntityType.TNT

    override fun isExperienceOrb(): Boolean = type == MinestomEntityType.EXPERIENCE_ORB

    override fun isLiving(): Boolean = type.registry()?.shouldSendAttributes() == true

    override fun isAnimal(): Boolean = meta is AnimalMeta

    override fun isAmbient(): Boolean = meta is AmbientCreatureMeta

    override fun isNPC(): Boolean = meta is AbstractVillagerMeta || type == MinestomEntityType.MANNEQUIN

    override fun isGolem(): Boolean = meta is AbstractGolemMeta

    override fun isTamed(): Boolean = (meta as? TameableAnimalMeta)?.isTamed == true

    override fun isTagged(): Boolean = entity.get(DataComponents.CUSTOM_NAME) != null

    override fun isArmorStand(): Boolean = meta is ArmorStandMeta

    override fun isPasteable(): Boolean = !isPlayerDerived

    override fun isWaterCreature(): Boolean = meta is WaterAnimalMeta

    private companion object {
        val projectileTypes =
            setOf(
                "minecraft:arrow",
                "minecraft:breeze_wind_charge",
                "minecraft:dragon_fireball",
                "minecraft:egg",
                "minecraft:ender_pearl",
                "minecraft:experience_bottle",
                "minecraft:fireball",
                "minecraft:firework_rocket",
                "minecraft:fishing_bobber",
                "minecraft:lingering_potion",
                "minecraft:llama_spit",
                "minecraft:shulker_bullet",
                "minecraft:small_fireball",
                "minecraft:snowball",
                "minecraft:spectral_arrow",
                "minecraft:splash_potion",
                "minecraft:trident",
                "minecraft:wind_charge",
                "minecraft:wither_skull",
            )
    }
}

private class MinestomEntityAdapter(
    private val entity: Entity,
    private val world: World,
) : WorldEditEntity {
    private val properties = MinestomEntityProperties(entity)

    override fun getLocation(): Location = MinestomAdapter.asLocation(world, entity.position)

    override fun setLocation(location: Location): Boolean {
        entity.teleport(MinestomAdapter.toPosition(location)).join()
        return true
    }

    override fun getExtent(): Extent = world

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getFacet(cls: Class<out T>?): T? = if (cls == EntityProperties::class.java) properties as T else null

    override fun getState(): BaseEntity {
        val typeId = entity.entityType.key().asString()
        val type = WorldEditEntityType.REGISTRY.get(typeId) ?: WorldEditEntityType(typeId)
        val nbt = entityNbt()
        return if (nbt.isEmpty) {
            BaseEntity(type)
        } else {
            BaseEntity(type, LazyReference.computed(MinestomAdapter.asLinTag(nbt)))
        }
    }

    override fun remove(): Boolean = entity.remove().let { true }

    private fun entityNbt(): CompoundBinaryTag {
        val builder = CompoundBinaryTag.builder()
        entity.get(DataComponents.CUSTOM_DATA)?.nbt()?.let(builder::put)
        if (entity is ItemEntity) {
            builder.put("Item", entity.itemStack.toItemNBT(MinecraftServer.process()))
        }

        builder.putByte("NoGravity", entity.hasNoGravity().toNbtByte())
        builder.putByte("Silent", entity.isSilent.toNbtByte())
        builder.putByte("Glowing", entity.isGlowing.toNbtByte())
        builder.putByte("Invisible", entity.isInvisible.toNbtByte())
        builder.putByte("CustomNameVisible", entity.isCustomNameVisible.toNbtByte())
        entity.get(DataComponents.CUSTOM_NAME)?.let { customName ->
            builder.putString("CustomName", GsonComponentSerializer.gson().serialize(customName))
        } ?: builder.remove("CustomName")
        return builder.build()
    }

    private fun Boolean.toNbtByte(): Byte = if (this) 1 else 0
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

    override fun getFullBlock(position: BlockVector3): BaseBlock {
        checkLoadedChunk(position)
        val block = getWorld().getBlock(position.x(), position.y(), position.z())
        val state = BlockStateIdAccess.getBlockStateById(block.stateId())!!
        val nbt = block.nbt()
        val handler = block.handler()
        if (nbt == null && handler == null) return state.toBaseBlock()

        val builder = CompoundBinaryTag.builder()
        nbt?.let(builder::put)
        handler?.let { builder.putString("id", it.key.asString()) }
        return state.toBaseBlock(MinestomAdapter.asLinTag(builder.build()))
    }

    override fun <B : BlockStateHolder<B>?> setBlock(
        position: BlockVector3?,
        block: B,
        sideEffects: SideEffectSet?,
    ): Boolean {
        position?.let { checkLoadedChunk(it) }
        return nativeAccess.setFullBlock(position, block, sideEffects)
    }

    override fun getEntities(region: Region?): MutableList<out WorldEditEntity> =
        getWorld()
            .entities
            .asSequence()
            .filter { entity -> region == null || region.contains(MinestomAdapter.asBlockVector(entity.position)) }
            .map { MinestomEntityAdapter(it, this) }
            .toMutableList()

    override fun getEntities(): MutableList<out WorldEditEntity> =
        getWorld().entities.map { MinestomEntityAdapter(it, this) }.toMutableList()

    override fun createEntity(
        location: Location?,
        entityState: BaseEntity?,
    ): WorldEditEntity? {
        if (location == null || entityState == null) return null

        val type = MinestomEntityType.fromKey(entityState.type.id()) ?: return null
        if (type == MinestomEntityType.PLAYER) return null
        val nbt = entityState.nbt?.let(MinestomAdapter::asNBT)
        val entity =
            when {
                type == MinestomEntityType.ITEM -> {
                    val itemNbt = nbt?.get("Item") as? CompoundBinaryTag ?: return null
                    val item =
                        runCatching { ItemStack.fromItemNBT(itemNbt, MinecraftServer.process()) }
                            .getOrNull() ?: return null
                    ItemEntity(item)
                }

                type.registry()?.shouldSendAttributes() == true -> LivingEntity(type)
                else -> Entity(type)
            }

        nbt?.let { applyEntityNbt(entity, it) }
        return try {
            entity.setInstance(getWorld(), MinestomAdapter.toPosition(location)).join()
            MinestomEntityAdapter(entity, this)
        } catch (_: Exception) {
            entity.remove()
            null
        }
    }

    private fun applyEntityNbt(
        entity: Entity,
        nbt: CompoundBinaryTag,
    ) {
        entity.set(DataComponents.CUSTOM_DATA, CustomData(nbt))
        entity.setNoGravity(nbt.getByte("NoGravity").toInt() != 0)
        entity.setSilent(nbt.getByte("Silent").toInt() != 0)
        entity.setGlowing(nbt.getByte("Glowing").toInt() != 0)
        entity.isInvisible = nbt.getByte("Invisible").toInt() != 0
        entity.isCustomNameVisible = nbt.getByte("CustomNameVisible").toInt() != 0
        nbt.getString("CustomName").takeIf(String::isNotEmpty)?.let { customName ->
            runCatching { GsonComponentSerializer.gson().deserialize(customName) }
                .getOrNull()
                ?.let { entity.set(DataComponents.CUSTOM_NAME, it) }
        }
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
