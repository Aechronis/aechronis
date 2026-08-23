package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.PlayerHand
import net.minestom.server.entity.metadata.other.ItemFrameMeta
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.instance.InstanceChunkUnloadEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerChunkLoadEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.utils.Direction
import net.minestom.server.utils.Rotation
import java.util.concurrent.ConcurrentHashMap

object ItemFrames {
    private const val FRAME_DATA = "aechronis:item_frames"

    private data class Frame(
        val instance: Instance,
        val support: BlockVec,
        val face: BlockFace,
        val glowing: Boolean,
    )

    private val frames = ConcurrentHashMap<Entity, Frame>()

    fun init() {
        Vanilla.eventNode.addListener(PlayerUseItemOnBlockEvent::class.java, ::onUseOnBlock)
        Vanilla.eventNode.addListener(PlayerEntityInteractEvent::class.java, ::onInteract)
        Vanilla.eventNode.addListener(EntityAttackEvent::class.java, ::onAttack)
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, ::onSupportBreak)
        // Frames are recreated only when a player receives a chunk. Loading every world chunk at
        // startup is both unnecessary and races chunk unloading.
        Vanilla.eventNode.addListener(PlayerChunkLoadEvent::class.java, ::onPlayerChunkLoad)
        Vanilla.eventNode.addListener(InstanceChunkUnloadEvent::class.java, ::onChunkUnload)
    }

    private fun onUseOnBlock(event: PlayerUseItemOnBlockEvent) {
        val glowing = event.itemStack.material() == Material.GLOW_ITEM_FRAME
        if (!glowing && event.itemStack.material() != Material.ITEM_FRAME) return
        val instance = event.player.instance ?: return
        if (event.player.gameMode == GameMode.SPECTATOR) return
        val support = BlockVec(event.position)
        if (!instance.getBlock(support).isSolid) return
        if (frames.values.any { it.instance === instance && it.support == support && it.face == event.blockFace }) return

        val entity = Entity(if (glowing) EntityType.GLOW_ITEM_FRAME else EntityType.ITEM_FRAME)
        entity.editEntityMeta(ItemFrameMeta::class.java) { meta -> meta.direction = frameDirection(event.blockFace) }
        val position =
            support
                .add(0.5, 0.5, 0.5)
                .add(
                    event.blockFace
                        .toDirection()
                        .vec()
                        .mul(0.46875),
                ).asPos()
        entity.setInstance(instance, position)
        val frame = Frame(instance, support, event.blockFace, glowing)
        frames[entity] = frame
        saveAnchor(frame)
        if (event.player.gameMode != GameMode.CREATIVE) consume(event.player, event.hand, event.itemStack)
    }

    private fun onInteract(event: PlayerEntityInteractEvent) {
        if (event.hand != PlayerHand.MAIN) return
        val frame = frames[event.target] ?: return
        val entity = event.target
        val meta = entity.entityMeta as? ItemFrameMeta ?: return
        val held = event.player.itemInMainHand
        if (meta.item.isAir && !held.isAir) {
            meta.item = held.withAmount(1)
            if (event.player.gameMode != GameMode.CREATIVE) event.player.itemInMainHand = held.withAmount(held.amount() - 1)
        } else if (!meta.item.isAir) {
            meta.rotation = meta.rotation.rotateClockwise()
        }
        saveAnchor(frame)
    }

    private fun onAttack(event: EntityAttackEvent) {
        val frame = frames.remove(event.target) ?: return
        val entity = event.target
        saveAnchor(frame)
        val player = event.entity as? net.minestom.server.entity.Player
        val meta = entity.entityMeta as? ItemFrameMeta
        if (player?.gameMode != GameMode.CREATIVE) {
            val position = entity.position
            Items.spawn(frame.instance, position, ItemStack.of(if (frame.glowing) Material.GLOW_ITEM_FRAME else Material.ITEM_FRAME))
            meta?.item?.takeUnless(ItemStack::isAir)?.let { Items.spawn(frame.instance, position, it) }
        }
        entity.remove()
    }

    private fun onSupportBreak(event: PlayerBlockBreakEvent) {
        if (event.isCancelled) return
        val support = event.blockPosition
        frames.entries
            .filter { (entity, frame) -> frame.instance === event.instance && frame.support == support }
            .forEach { (entity, _) -> onAttack(EntityAttackEvent(event.player, entity)) }
    }

    private fun onPlayerChunkLoad(event: PlayerChunkLoadEvent) {
        val instance = event.player.instance ?: return
        val chunk = instance.getChunk(event.chunkX, event.chunkZ) ?: return
        restoreChunk(chunk)
    }

    private fun onChunkUnload(event: InstanceChunkUnloadEvent) {
        val instance = event.instance
        val chunkX = event.chunkX
        val chunkZ = event.chunkZ
        // Minestom removes non-player entities with their chunk. Forget their old anchors so a
        // later player chunk load can restore them.
        frames.entries.removeIf { (_, frame) ->
            frame.instance === instance && frame.support.chunkX() == chunkX && frame.support.chunkZ() == chunkZ
        }
    }

    private fun saveAnchor(frame: Frame) {
        val block = frame.instance.getBlock(frame.support)
        if (block.isAir) return
        val anchored =
            frames.entries.filter { (_, value) ->
                value.instance === frame.instance && value.support == frame.support
            }
        if (anchored.isEmpty()) {
            frame.instance.setBlock(frame.support, block.withNbt(block.nbtOrEmpty().remove(FRAME_DATA)), false)
            return
        }

        val records = ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
        anchored.forEach { (entity, value) ->
            val meta = entity.entityMeta as? ItemFrameMeta ?: return@forEach
            val record =
                CompoundBinaryTag
                    .builder()
                    .putString("face", value.face.name.lowercase())
                    .putBoolean("glowing", value.glowing)
                    .putByte("rotation", meta.rotation.ordinal.toByte())
            if (!meta.item.isAir) record.put("item", meta.item.toItemNBT())
            records.add(record.build())
        }
        frame.instance.setBlock(frame.support, block.withNbt(block.nbtOrEmpty().put(FRAME_DATA, records.build())), false)
    }

    private fun restoreChunk(chunk: Chunk) {
        if (!chunk.isLoaded) return
        val instance = chunk.instance
        val records = mutableListOf<Pair<BlockVec, CompoundBinaryTag>>()
        val minY = chunk.minSection * 16
        val maxY = chunk.maxSection * 16

        // Read from the chunk supplied by the event while it is locked. Instance#getBlock can
        // consult an already-unloaded chunk while the asynchronous loader is completing.
        chunk.lockReadLock()
        try {
            for (x in chunk.chunkX * 16..<chunk.chunkX * 16 + 16) {
                for (z in chunk.chunkZ * 16..<chunk.chunkZ * 16 + 16) {
                    for (y in minY..<maxY) {
                        val support = BlockVec(x, y, z)
                        chunk.getBlock(support).nbtOrEmpty().getList(FRAME_DATA, BinaryTagTypes.COMPOUND).forEach { entry ->
                            (entry as? CompoundBinaryTag)?.let { records += support to it }
                        }
                    }
                }
            }
        } finally {
            chunk.unlockReadLock()
        }

        synchronized(frames) {
            if (!chunk.isLoaded || instance.getChunk(chunk.chunkX, chunk.chunkZ) !== chunk) return
            for ((support, record) in records) {
                val face =
                    runCatching { BlockFace.valueOf((record.getString("face", "") ?: "").uppercase()) }.getOrNull() ?: continue
                if (frames.values.any { it.instance === instance && it.support == support && it.face == face }) continue
                val glowing = record.getBoolean("glowing", false)
                val entity = Entity(if (glowing) EntityType.GLOW_ITEM_FRAME else EntityType.ITEM_FRAME)
                entity.editEntityMeta(ItemFrameMeta::class.java) { meta ->
                    meta.direction = frameDirection(face)
                    meta.rotation = Rotation.entries.getOrElse(record.getByte("rotation", 0).toInt()) { Rotation.NONE }
                    record.getCompound("item")?.let { item ->
                        meta.item = runCatching { ItemStack.fromItemNBT(item) }.getOrDefault(ItemStack.AIR)
                    }
                }
                entity.setInstance(instance, support.add(0.5, 0.5, 0.5).add(face.toDirection().vec().mul(0.46875)).asPos())
                frames[entity] = Frame(instance, support, face, glowing)
            }
        }
    }

    private fun consume(
        player: net.minestom.server.entity.Player,
        hand: PlayerHand,
        stack: ItemStack,
    ) {
        player.setItemInHand(hand, stack.withAmount(stack.amount() - 1))
    }

    /**
     * The item-frame spawn direction is the direction towards its attachment block, whereas a
     * use-on-block face points away from that block. Vanilla's ItemFrame entity therefore uses
     * the opposite direction for the entity metadata.
     */
    private fun frameDirection(face: BlockFace): Direction = face.oppositeFace.toDirection()
}
