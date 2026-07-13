package net.aechronis.vanilla.managers

import net.aechronis.vanilla.listeners.StorageListener
import net.aechronis.vanilla.objects.BlockKey
import net.aechronis.vanilla.objects.StorageContents
import net.aechronis.vanilla.serdes.StorageDeserializer
import net.aechronis.vanilla.serdes.StorageSerializer
import net.kyori.adventure.nbt.BinaryTagIO
import net.minestom.server.coordinate.Vec
import net.minestom.server.instance.Instance
import net.minestom.server.inventory.Inventory
import java.nio.file.Files
import java.nio.file.Path
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.concurrent.ConcurrentHashMap

object Storage {
    val barrels = ConcurrentHashMap<BlockKey, StorageContents>()
    val inventoryToKey = ConcurrentHashMap<Inventory, BlockKey>()

    private lateinit var root: Path

    fun init(root: Path) {
        val timeStart = System.currentTimeMillis()
        Files.createDirectories(root)
        this.root = root
        StorageListener.init()

        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("├─ Storage enabled in ${timeLoad}ms")
    }

    fun keyFor(
        instance: Instance,
        pos: Vec,
    ): BlockKey = BlockKey(instance, pos)

    fun loadOrCreate(key: BlockKey): StorageContents {
        barrels[key]?.let { return it }
        val file = fileFor(key)
        val contents =
            if (Files.exists(file)) {
                Files.newInputStream(file).use { input ->
                    val named = BinaryTagIO.reader().readNamed(input, BinaryTagIO.Compression.GZIP)
                    StorageDeserializer.deserialize(named.value)
                }
            } else {
                StorageContents()
            }
        barrels[key] = contents
        inventoryToKey[contents.inventory] = key
        return contents
    }

    fun register(
        key: BlockKey,
        contents: StorageContents,
    ) {
        barrels[key] = contents
        inventoryToKey[contents.inventory] = key
    }

    fun save(key: BlockKey) {
        val contents = barrels[key] ?: return
        val data = StorageSerializer.serialize(contents.inventory)
        val file = fileFor(key)
        Files.createDirectories(file.parent)
        Files.newOutputStream(file).use { out ->
            BinaryTagIO.writer().writeNamed(
                SimpleImmutableEntry("", data),
                out,
                BinaryTagIO.Compression.GZIP,
            )
        }
    }

    fun saveAll() {
        for (key in barrels.keys) {
            try {
                save(key)
            } catch (e: Exception) {
                System.err.println("Failed to save storage at $key: ${e.message}")
            }
        }
    }

    fun remove(key: BlockKey) {
        val contents = barrels.remove(key)
        if (contents != null) {
            inventoryToKey.remove(contents.inventory)
        }
        val file = fileFor(key)
        Files.deleteIfExists(file)
    }

    private fun fileFor(key: BlockKey): Path {
        val x = key.pos.x().toInt()
        val y = key.pos.y().toInt()
        val z = key.pos.z().toInt()
        return root
            .resolve(key.instance.uuid.toString())
            .resolve("${x}_${y}_$z.dat")
    }
}
