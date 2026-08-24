package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.nbt.BinaryTag
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
import net.minestom.server.event.instance.InstanceChunkLoadEvent
import net.minestom.server.event.instance.InstanceChunkUnloadEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.tag.Tag
import net.minestom.server.utils.Rotation
import java.util.concurrent.ConcurrentHashMap

object ItemFrames {
    private const val FRAME_DATA = "aechronis:item_frames"

    // Vanilla places the frame in the adjacent block, 0.46875 blocks back from its centre.
    // Measured from the support block's centre, that is 1 - 0.46875 blocks towards its clicked face.
    private const val FRAME_OFFSET_FROM_SUPPORT = 0.53125

    private val frameAnchorsTag: Tag<BinaryTag> = Tag.NBT("aechronis:item_frame_anchors")
    private val frameAnchorsIndexedTag = Tag.Boolean("aechronis:item_frame_anchors_indexed")

    private data class Frame(
        val instance: Instance,
        val support: BlockVec,
        val face: BlockFace,
        val glowing: Boolean,
    )

    private data class FrameKey(
        val instance: Instance,
        val support: BlockVec,
        val face: BlockFace,
    )

    private val frames = ConcurrentHashMap<Entity, Frame>()
    private val framesByAnchor = ConcurrentHashMap<FrameKey, Entity>()

    fun init() {
        Vanilla.eventNode.addListener(PlayerUseItemOnBlockEvent::class.java, ::onUseOnBlock)
        Vanilla.eventNode.addListener(PlayerEntityInteractEvent::class.java, ::onInteract)
        Vanilla.eventNode.addListener(EntityAttackEvent::class.java, ::onAttack)
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, ::onSupportBreak)
        // Anvil loads chunks on virtual threads. Restore only this chunk's indexed frame anchors
        // on the next instance tick; never scan while a player is being sent a chunk.
        Vanilla.eventNode.addListener(InstanceChunkLoadEvent::class.java, ::onChunkLoad)
        Vanilla.eventNode.addListener(InstanceChunkUnloadEvent::class.java, ::onChunkUnload)
    }

    private fun onUseOnBlock(event: PlayerUseItemOnBlockEvent) {
        val glowing = event.itemStack.material() == Material.GLOW_ITEM_FRAME
        if (!glowing && event.itemStack.material() != Material.ITEM_FRAME) return
        val instance = event.player.instance ?: return
        if (event.player.gameMode == GameMode.SPECTATOR) return
        val support = BlockVec(event.position)
        if (!instance.getBlock(support).isSolid) return
        val key = FrameKey(instance, support, event.blockFace)
        if (framesByAnchor.containsKey(key)) return

        val entity = createFrameEntity(glowing, event.blockFace)
        val position = framePosition(support, event.blockFace)
        val frame = Frame(instance, support, event.blockFace, glowing)
        if (framesByAnchor.putIfAbsent(key, entity) != null) return
        entity.setInstance(instance, position)
        frames[entity] = frame
        saveAnchor(frame)
        if (event.player.gameMode != GameMode.CREATIVE) consume(event.player, event.hand, event.itemStack)
    }

    private fun onInteract(event: PlayerEntityInteractEvent) {
        val frame = frames[event.target] ?: return
        val entity = event.target
        val meta = entity.entityMeta as? ItemFrameMeta ?: return
        val held = event.player.getItemInHand(event.hand)
        if (meta.item.isAir && !held.isAir) {
            meta.item = held.withAmount(1)
            if (event.player.gameMode != GameMode.CREATIVE) {
                event.player.setItemInHand(event.hand, held.withAmount(held.amount() - 1))
            }
        } else if (!meta.item.isAir) {
            meta.rotation = meta.rotation.rotateClockwise()
        }
        saveAnchor(frame)
    }

    private fun onAttack(event: EntityAttackEvent) {
        val entity = event.target
        val frame = frames.remove(entity) ?: return
        framesByAnchor.remove(FrameKey(frame.instance, frame.support, frame.face), entity)
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

    private fun onChunkLoad(event: InstanceChunkLoadEvent) {
        val chunk = event.chunk
        val (indexed, anchors) = anchorsForLoad(chunk)
        event.instance.scheduleNextTick { instance ->
            if (!chunk.isLoaded || instance.getChunk(chunk.chunkX, chunk.chunkZ) !== chunk) return@scheduleNextTick
            if (!indexed) writeAnchors(chunk, anchors)
            restoreAnchors(chunk, anchors)
        }
    }

    private fun onChunkUnload(event: InstanceChunkUnloadEvent) {
        val instance = event.instance
        val chunkX = event.chunkX
        val chunkZ = event.chunkZ
        // Minestom removes non-player entities with their chunk. Forget their old anchors so a
        // later physical chunk load can restore them.
        frames.entries
            .filter { (_, frame) ->
                frame.instance === instance && frame.support.chunkX() == chunkX && frame.support.chunkZ() == chunkZ
            }.forEach { (entity, frame) ->
                frames.remove(entity, frame)
                framesByAnchor.remove(FrameKey(frame.instance, frame.support, frame.face), entity)
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
            updateAnchorIndex(frame.instance, frame.support, present = false)
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
        updateAnchorIndex(frame.instance, frame.support, present = true)
    }

    /** Returns the persisted index, or discovers legacy anchors once off the tick thread. */
    private fun anchorsForLoad(chunk: Chunk): Pair<Boolean, Set<BlockVec>> {
        chunk.lockReadLock()
        try {
            if (chunk.getTag(frameAnchorsIndexedTag) == true) return true to indexedAnchors(chunk)
            // Compatibility for existing item frames. The scan is done by Anvil's virtual loader
            // thread, never by Player#sendPendingChunks or an instance tick.
            return false to scanFrameAnchors(chunk)
        } finally {
            chunk.unlockReadLock()
        }
    }

    private fun indexedAnchors(chunk: Chunk): Set<BlockVec> =
        ((chunk.getTag(frameAnchorsTag) as? ListBinaryTag) ?: return emptySet())
            .mapNotNull { entry ->
                val record = entry as? CompoundBinaryTag ?: return@mapNotNull null
                val x = record.getInt("x", Int.MIN_VALUE)
                val y = record.getInt("y", Int.MIN_VALUE)
                val z = record.getInt("z", Int.MIN_VALUE)
                if (x == Int.MIN_VALUE || y == Int.MIN_VALUE || z == Int.MIN_VALUE) null else BlockVec(x, y, z)
            }.filterTo(mutableSetOf()) { it.chunkX() == chunk.chunkX && it.chunkZ() == chunk.chunkZ }

    private fun scanFrameAnchors(chunk: Chunk): Set<BlockVec> {
        val anchors = mutableSetOf<BlockVec>()
        for (x in chunk.chunkX * 16..<chunk.chunkX * 16 + 16) {
            for (z in chunk.chunkZ * 16..<chunk.chunkZ * 16 + 16) {
                for (y in chunk.minSection * 16..<chunk.maxSection * 16) {
                    val support = BlockVec(x, y, z)
                    if (
                        !chunk
                            .getBlock(support)
                            .nbtOrEmpty()
                            .getList(FRAME_DATA, BinaryTagTypes.COMPOUND)
                            .isEmpty()
                    ) {
                        anchors += support
                    }
                }
            }
        }
        return anchors
    }

    private fun writeAnchors(
        chunk: Chunk,
        anchors: Collection<BlockVec>,
    ) {
        val records = ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
        anchors.forEach { anchor ->
            records.add(
                CompoundBinaryTag
                    .builder()
                    .putInt("x", anchor.blockX())
                    .putInt("y", anchor.blockY())
                    .putInt("z", anchor.blockZ())
                    .build(),
            )
        }
        chunk.setTag(frameAnchorsTag, records.build())
        chunk.setTag(frameAnchorsIndexedTag, true)
    }

    private fun updateAnchorIndex(
        instance: Instance,
        support: BlockVec,
        present: Boolean,
    ) {
        val chunk = instance.getChunk(support.chunkX(), support.chunkZ()) ?: return
        chunk.lockWriteLock()
        try {
            val anchors = indexedAnchors(chunk).toMutableSet()
            if (present) anchors += support else anchors -= support
            writeAnchors(chunk, anchors)
        } finally {
            chunk.unlockWriteLock()
        }
    }

    private fun restoreAnchors(
        chunk: Chunk,
        anchors: Collection<BlockVec>,
    ) {
        if (!chunk.isLoaded) return
        val instance = chunk.instance
        for (support in anchors) {
            val records = instance.getBlock(support).nbtOrEmpty().getList(FRAME_DATA, BinaryTagTypes.COMPOUND)
            for (entry in records) {
                val record = entry as? CompoundBinaryTag ?: continue
                val face =
                    runCatching { BlockFace.valueOf((record.getString("face", "") ?: "").uppercase()) }.getOrNull() ?: continue
                val key = FrameKey(instance, support, face)
                if (framesByAnchor.containsKey(key)) continue
                val glowing = record.getBoolean("glowing", false)
                val entity = createFrameEntity(glowing, face)
                if (framesByAnchor.putIfAbsent(key, entity) != null) continue
                entity.editEntityMeta(ItemFrameMeta::class.java) { meta ->
                    meta.rotation = Rotation.entries.getOrElse(record.getByte("rotation", 0).toInt()) { Rotation.NONE }
                    record.getCompound("item")?.let { item ->
                        meta.item = runCatching { ItemStack.fromItemNBT(item) }.getOrDefault(ItemStack.AIR)
                    }
                }
                entity.setInstance(instance, framePosition(support, face))
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

    private fun createFrameEntity(
        glowing: Boolean,
        face: BlockFace,
    ): Entity =
        Entity(if (glowing) EntityType.GLOW_ITEM_FRAME else EntityType.ITEM_FRAME).also { entity ->
            // AbstractDecorationEntity is static and has no gravity in vanilla.
            entity.setHasPhysics(false)
            entity.setNoGravity(true)
            entity.editEntityMeta(ItemFrameMeta::class.java) { meta -> meta.direction = face.toDirection() }
        }

    private fun framePosition(
        support: BlockVec,
        face: BlockFace,
    ) = support.add(0.5, 0.5, 0.5).add(face.toDirection().vec().mul(FRAME_OFFSET_FROM_SUPPORT)).asPos()
}
