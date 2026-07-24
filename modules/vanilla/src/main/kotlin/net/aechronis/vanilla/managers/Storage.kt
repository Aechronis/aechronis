package net.aechronis.vanilla.managers

import net.aechronis.vanilla.listeners.StorageListener
import net.aechronis.vanilla.objects.BlockKey
import net.aechronis.vanilla.objects.StorageContents
import net.aechronis.vanilla.serdes.StorageDeserializer
import net.aechronis.vanilla.serdes.StorageSerializer
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.ListBinaryTag
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockHandler
import net.minestom.server.inventory.Inventory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object Storage {
    val barrels = ConcurrentHashMap<BlockKey, StorageContents>()
    val inventoryToKey = ConcurrentHashMap<Inventory, BlockKey>()

    private val barrelKey = Key.key("minecraft:barrel")
    private val defaultBarrelHandler =
        object : BlockHandler {
            override fun getKey(): Key = barrelKey
        }
    private var legacyRoot: Path? = null

    fun init(legacyRoot: Path) {
        val timeStart = System.currentTimeMillis()
        this.legacyRoot = legacyRoot
        val blockManager = MinecraftServer.getBlockManager()
        if (blockManager.getHandler(barrelKey.asString()) == null) {
            blockManager.registerHandler(barrelKey) { defaultBarrelHandler }
        }
        StorageListener.init()

        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("├─ Storage enabled in ${timeLoad}ms")
    }

    fun keyFor(
        instance: Instance,
        pos: Point,
    ): BlockKey = BlockKey(instance, pos.asVec())

    fun loadOrCreate(key: BlockKey): StorageContents {
        val block = key.instance.getBlock(key.pos)
        val blockNbt = block.nbtOrEmpty()
        val file = legacyFileFor(key)
        var created = false
        var migratedFile: Path? = null
        val contents =
            barrels.computeIfAbsent(key) {
                created = true
                if (blockNbt.contains(StorageSerializer.ITEMS_KEY, BinaryTagTypes.LIST)) {
                    StorageDeserializer.deserialize(blockNbt)
                } else if (file != null && Files.exists(file)) {
                    migratedFile = file
                    Files.newInputStream(file).use { input ->
                        val named = BinaryTagIO.reader().readNamed(input, BinaryTagIO.Compression.GZIP)
                        StorageDeserializer.deserialize(named.value)
                    }
                } else {
                    StorageContents()
                }
            }
        inventoryToKey.putIfAbsent(contents.inventory, key)
        if (created && block.compare(Block.BARREL) && !blockNbt.contains(StorageSerializer.ITEMS_KEY, BinaryTagTypes.LIST)) {
            writeToBlock(key)
            migratedFile?.let { persistAndArchiveMigration(key, it) }
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
        val block = key.instance.getBlock(key.pos)
        if (!block.compare(Block.BARREL)) return false
        key.instance.setBlock(key.pos, withContents(block, contents), false)
        return true
    }

    fun saveAll() {
        val chunks = mutableSetOf<Chunk>()
        for (key in barrels.keys) {
            try {
                if (writeToBlock(key)) key.instance.getChunkAt(key.pos)?.let(chunks::add)
            } catch (e: Exception) {
                System.err.println("Failed to save storage at $key: ${e.message}")
            }
        }
        val saves = chunks.map { chunk -> chunk.instance.saveChunkToStorage(chunk) }
        try {
            CompletableFuture.allOf(*saves.toTypedArray()).join()
        } catch (e: Exception) {
            System.err.println("Failed to save one or more storage chunks: ${e.message}")
        }
    }

    fun remove(key: BlockKey) {
        val contents = barrels.remove(key)
        if (contents != null) {
            inventoryToKey.remove(contents.inventory)
        }
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

    private fun persistAndArchiveMigration(
        key: BlockKey,
        file: Path,
    ) {
        val chunk = key.instance.getChunkAt(key.pos) ?: return
        key.instance.saveChunkToStorage(chunk).join()
        Files.move(
            file,
            file.resolveSibling("${file.fileName}.migrated"),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun legacyFileFor(key: BlockKey): Path? {
        val root = legacyRoot ?: return null
        val x = key.pos.x().toInt()
        val y = key.pos.y().toInt()
        val z = key.pos.z().toInt()
        return root
            .resolve(key.instance.uuid.toString())
            .resolve("${x}_${y}_$z.dat")
    }
}
