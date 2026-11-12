package net.aechronis.vanilla.managers

import net.aechronis.server.modules.ModuleBlocks
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.StorageListener
import net.aechronis.vanilla.objects.BlockKey
import net.aechronis.vanilla.objects.StorageContents
import net.aechronis.vanilla.serdes.StorageDeserializer
import net.aechronis.vanilla.serdes.StorageSerializer
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.ListBinaryTag
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.Player
import net.minestom.server.event.instance.InstanceChunkUnloadEvent
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockHandler
import net.minestom.server.inventory.Inventory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

enum class StorageAccess {
    INTERACT,
    BREAK,
}

object Storage {
    val barrels = ConcurrentHashMap<BlockKey, StorageContents>()
    val inventoryToKey = ConcurrentHashMap<Inventory, BlockKey>()

    private val barrelKey = Key.key("minecraft:barrel")
    private val defaultBarrelHandler =
        object : BlockHandler {
            override fun getKey(): Key = barrelKey
        }

    @Volatile
    private var accessChecker: ((Player, Point, StorageAccess) -> Boolean)? = null

    fun setAccessChecker(checker: ((Player, Point, StorageAccess) -> Boolean)?) {
        accessChecker = checker
    }

    fun hasAccess(
        player: Player,
        position: Point,
        access: StorageAccess,
    ): Boolean = accessChecker?.invoke(player, position, access) ?: true

    fun init() {
        ModuleBlocks.registerHandlerIfAbsent(barrelKey) { defaultBarrelHandler }
        StorageListener.init()
        Vanilla.eventNode.addListener(InstanceChunkUnloadEvent::class.java, ::onChunkUnload)
    }

    // Every inventory change is written to block NBT as it happens, so the live copy holds nothing
    // the chunk does not. Dropping it keeps a stale inventory from outliving its block.
    private fun onChunkUnload(event: InstanceChunkUnloadEvent) {
        for ((key, contents) in barrels) {
            if (key.instance !== event.instance || key.pos.chunkX() != event.chunkX || key.pos.chunkZ() != event.chunkZ) continue
            contents.inventory.viewers
                .toList()
                .forEach { it.closeInventory() }
            remove(key)
        }
    }

    fun keyFor(
        instance: Instance,
        pos: Point,
    ): BlockKey = BlockKey(instance, pos.asVec())

    fun loadOrCreate(key: BlockKey): StorageContents {
        val block = key.instance.getBlock(key.pos)
        val blockNbt = block.nbtOrEmpty()
        var created = false
        val contents =
            barrels.computeIfAbsent(key) {
                created = true
                if (blockNbt.contains(StorageSerializer.ITEMS_KEY, BinaryTagTypes.LIST)) {
                    StorageDeserializer.deserialize(blockNbt)
                } else {
                    StorageContents()
                }
            }
        inventoryToKey.putIfAbsent(contents.inventory, key)
        if (created && block.compare(Block.BARREL) && !blockNbt.contains(StorageSerializer.ITEMS_KEY, BinaryTagTypes.LIST)) {
            writeToBlock(key)
        }
        return contents
    }

    fun register(
        key: BlockKey,
        contents: StorageContents,
    ) {
        barrels.put(key, contents)?.let { previous ->
            inventoryToKey.remove(previous.inventory)
        }
        inventoryToKey[contents.inventory] = key
    }

    fun withContents(
        block: Block,
        contents: StorageContents,
    ): Block = withItems(block, StorageSerializer.serializeItems(contents.inventory))

    fun save(key: BlockKey) {
        writeToBlock(key)
    }

    private fun writeToBlock(key: BlockKey): Boolean {
        val contents = barrels[key] ?: return false
        if (!key.instance.isChunkLoaded(key.pos)) return false
        val block = key.instance.getBlock(key.pos)
        if (!block.compare(Block.BARREL)) return false
        val updated = withContents(block, contents)
        // Rewriting an unchanged barrel would mark its chunk modified at every checkpoint.
        if (updated != block) key.instance.setBlock(key.pos, updated, false)
        return true
    }

    fun saveAll() {
        val chunks = flushToWorld()
        val saves = chunks.map { chunk -> chunk.instance.saveChunkToStorage(chunk) }
        CompletableFuture.allOf(*saves.toTypedArray()).join()
    }

    // copies every live barrel inventory into block NBT for the next world save
    fun flushToWorld(): Set<Chunk> {
        val chunks = mutableSetOf<Chunk>()
        var failure: Throwable? = null
        for (key in barrels.keys) {
            try {
                if (writeToBlock(key)) key.instance.getChunkAt(key.pos)?.let(chunks::add)
            } catch (e: Exception) {
                System.err.println("Failed to save storage at $key: ${e.message}")
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        failure?.let { throw it }
        return chunks
    }

    fun remove(key: BlockKey) {
        val contents = barrels.remove(key)
        if (contents != null) {
            inventoryToKey.remove(contents.inventory)
        }
    }

    fun shutdown() {
        accessChecker = null
        inventoryToKey.keys.forEach { inventory -> inventory.viewers.toList().forEach { it.closeInventory() } }
        inventoryToKey.clear()
        barrels.clear()
    }

    private fun withItems(
        block: Block,
        items: ListBinaryTag,
    ): Block {
        val handler =
            block.handler()
                ?: MinecraftServer.getBlockManager().getHandler(barrelKey.asString())
                ?: defaultBarrelHandler
        return block
            .withNbt(block.nbtOrEmpty().put(StorageSerializer.ITEMS_KEY, items))
            .withHandler(handler)
    }
}
