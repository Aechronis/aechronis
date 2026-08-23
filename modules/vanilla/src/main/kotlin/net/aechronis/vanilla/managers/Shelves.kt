package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerChunkLoadEvent
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockHandler
import net.minestom.server.item.ItemStack
import net.minestom.server.tag.Tag

object Shelves {
    private const val ITEMS = "Items"

    fun init() {
        val manager = MinecraftServer.getBlockManager()
        Block
            .staticRegistry()
            .values()
            .filter { it.key().asString().endsWith("_shelf") }
            .forEach { block -> manager.registerHandler(block.key()) { ShelfHandler(block.defaultState()) } }
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, ::onBreak)
        Vanilla.eventNode.addListener(PlayerChunkLoadEvent::class.java, ::onPlayerChunkLoad)
    }

    private fun onBreak(event: PlayerBlockBreakEvent) {
        if (event.isCancelled || !isShelf(event.block)) return
        val instance = event.instance
        val position = event.blockPosition
        val dropPosition = position.add(0.5, 0.5, 0.5).asPos()
        items(event.block).filterNot(ItemStack::isAir).forEach { Items.spawn(instance, dropPosition, it) }
    }

    private fun isShelf(block: Block): Boolean = block.key().asString().endsWith("_shelf")

    private fun onPlayerChunkLoad(event: PlayerChunkLoadEvent) {
        val instance = event.player.instance ?: return
        instance.getChunk(event.chunkX, event.chunkZ)?.let(::restoreChunk)
    }

    /** Rebind persisted shelves only after their chunk is actually being viewed. */
    private fun restoreChunk(chunk: Chunk) {
        if (!chunk.isLoaded) return
        val shelves = mutableListOf<Pair<BlockVec, Block>>()
        chunk.lockReadLock()
        try {
            for (x in chunk.chunkX * 16..<chunk.chunkX * 16 + 16) {
                for (z in chunk.chunkZ * 16..<chunk.chunkZ * 16 + 16) {
                    for (y in chunk.minSection * 16..<chunk.maxSection * 16) {
                        val block = chunk.getBlock(x, y, z)
                        if (isShelf(block)) shelves += BlockVec(x, y, z) to block
                    }
                }
            }
        } finally {
            chunk.unlockReadLock()
        }
        val instance = chunk.instance
        if (!chunk.isLoaded || instance.getChunk(chunk.chunkX, chunk.chunkZ) !== chunk) return
        shelves.forEach { (position, block) ->
            val handler = MinecraftServer.getBlockManager().getHandler(block.key().asString())
            if (block.handler()?.key != handler?.key) instance.setBlock(position, block.withHandler(handler), false)
        }
    }

    private fun items(block: Block): MutableList<ItemStack> {
        val result = MutableList(3) { ItemStack.AIR }
        for (entry in block.nbtOrEmpty().getList(ITEMS, BinaryTagTypes.COMPOUND)) {
            val compound = entry as? CompoundBinaryTag ?: continue
            val slot = compound.getByte("Slot", (-1).toByte()).toInt()
            if (slot !in result.indices) continue
            val item =
                runCatching {
                    val builder = CompoundBinaryTag.builder()
                    compound.keySet().filter { it != "Slot" }.forEach { key -> builder.put(key, compound.get(key)!!) }
                    ItemStack.fromItemNBT(builder.build())
                }.getOrNull() ?: continue
            result[slot] = item
        }
        return result
    }

    private fun withItems(
        block: Block,
        values: List<ItemStack>,
    ): Block {
        val nbt = ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
        values.forEachIndexed { slot, item ->
            if (item.isAir) return@forEachIndexed
            val entry = CompoundBinaryTag.builder().putByte("Slot", slot.toByte())
            item.toItemNBT().keySet().forEach { key -> entry.put(key, item.toItemNBT().get(key)!!) }
            nbt.add(entry.build())
        }
        val handler = MinecraftServer.getBlockManager().getHandler(block.key().asString())
        return block.withNbt(block.nbtOrEmpty().put(ITEMS, nbt.build())).withHandler(handler)
    }

    private class ShelfHandler(
        private val type: Block,
    ) : BlockHandler {
        override fun getKey(): Key = type.key()

        override fun getBlockEntityTags(): Collection<Tag<*>> = listOf(Tag.NBT(ITEMS))

        override fun onInteract(interaction: BlockHandler.Interaction): Boolean {
            if (interaction.hand != net.minestom.server.entity.PlayerHand.MAIN) return false
            if (interaction.block.getProperty("powered") == "true") {
                swapPoweredGroup(interaction)
                return false
            }
            val slot = slotAt(interaction.block, interaction.cursorPosition)
            val stored = items(interaction.block)
            val held = interaction.player.itemInMainHand
            interaction.player.itemInMainHand = stored[slot]
            stored[slot] = held
            interaction.instance.setBlock(interaction.blockPosition, withItems(interaction.block, stored), false)
            return false
        }
    }

    private fun swapPoweredGroup(interaction: BlockHandler.Interaction) {
        val facing = interaction.block.getProperty("facing") ?: return
        val axisX = facing == "north" || facing == "south"
        val origin = interaction.blockPosition
        val candidates =
            (-2..2)
                .mapNotNull { offset ->
                    val position =
                        if (axisX) {
                            origin.add(offset.toDouble(), 0.0, 0.0)
                        } else {
                            origin.add(0.0, 0.0, offset.toDouble())
                        }
                    val block = interaction.instance.getBlock(position)
                    if (isShelf(block) &&
                        block.getProperty("powered") == "true" &&
                        block.getProperty("facing") == facing
                    ) {
                        position
                    } else {
                        null
                    }
                }.sortedBy { if (axisX) it.x() else it.z() }
        val center = candidates.indexOfFirst { it.sameBlock(origin) }
        if (center < 0) return
        val start = (center - 2).coerceAtLeast(0)
        val group = candidates.drop(start).take(3)
        if (group.isEmpty()) return

        val hotbarStart = 9 - group.size * 3
        group.forEachIndexed { shelfIndex, position ->
            val block = interaction.instance.getBlock(position)
            val stored = items(block)
            repeat(3) { slot ->
                val hotbarSlot = hotbarStart + shelfIndex * 3 + slot
                val playerItem = interaction.player.inventory.getItemStack(hotbarSlot)
                interaction.player.inventory.setItemStack(hotbarSlot, stored[slot])
                stored[slot] = playerItem
            }
            interaction.instance.setBlock(position, withItems(block, stored), false)
        }
    }

    private fun net.minestom.server.coordinate.Point.sameBlock(other: net.minestom.server.coordinate.Point): Boolean =
        x().toInt() == other.x().toInt() && y().toInt() == other.y().toInt() && z().toInt() == other.z().toInt()

    private fun slotAt(
        block: Block,
        cursor: net.minestom.server.coordinate.Point,
    ): Int {
        val coordinate =
            when (block.getProperty("facing")) {
                "north" -> cursor.x()
                "south" -> 1.0 - cursor.x()
                "east" -> 1.0 - cursor.z()
                "west" -> cursor.z()
                else -> cursor.x()
            }
        return (coordinate * 3.0).toInt().coerceIn(0, 2)
    }
}
