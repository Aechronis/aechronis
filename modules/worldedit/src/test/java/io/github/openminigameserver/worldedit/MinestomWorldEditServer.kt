package io.github.openminigameserver.worldedit

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.function.operation.Operation
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.session.ClipboardHolder
import io.github.openminigameserver.worldedit.platform.adapters.MinestomAdapter
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.event.player.PlayerChatEvent
import net.minestom.server.event.player.PlayerLoginEvent
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.ChunkGenerator
import net.minestom.server.instance.ChunkPopulator
import net.minestom.server.instance.batch.ChunkBatch
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.utils.Position
import net.minestom.server.world.biomes.Biome
import java.io.File
import java.util.*


object MinestomWorldEditServer {
    @JvmStatic
    fun main(args: Array<String>) {
        // Initialization
        val minecraftServer = MinecraftServer.init()
        val instanceManager = MinecraftServer.getInstanceManager()
        // Create the instance
        val instanceContainer = instanceManager.createInstanceContainer()
        // Set the ChunkGenerator
        instanceContainer.chunkGenerator = GeneratorDemo()
        // Enable the auto chunk loading (when players come close)
        instanceContainer.enableAutoChunkLoad(true)

        val globalEventHandler = MinecraftServer.getGlobalEventHandler()
        val schematicFile =
            File("""D:\Software\MultiMC\instances\1.16.4\.minecraft\config\worldedit\schematics\waiting-lobby.schem""")

        globalEventHandler.addEventCallback(PlayerChatEvent::class.java) { event: PlayerChatEvent ->
            val reader = BuiltInClipboardFormat.SPONGE_SCHEMATIC.getReader(schematicFile.inputStream())

            val clipboard = reader.read()

            val instance = event.player.instance!!
            val asWorld = MinestomAdapter.asWorld(instance)
            WorldEdit.getInstance().newEditSessionBuilder().world(asWorld).build().use { editSession ->
                val operation: Operation = ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(MinestomAdapter.asBlockVector(event.player.position))
                    .build()
                Operations.complete(operation)
            }

            event.player.apply {
                gameMode = GameMode.CREATIVE
                inventory.addItemStack(ItemStack(Material.WOODEN_AXE, 1))
            }

        }
        globalEventHandler.addEventCallback(PlayerLoginEvent::class.java) { event: PlayerLoginEvent ->
            val player = event.player
            event.setSpawningInstance(instanceContainer)
            player.gameMode = GameMode.SPECTATOR
            player.isFlying = true
            player.respawnPoint = Position(0f, 42f, 0f)
        }

        // Start the server on port 25565
        minecraftServer.start("localhost", 25565)
    }

    private class GeneratorDemo : ChunkGenerator {
        override fun generateChunkData(batch: ChunkBatch, chunkX: Int, chunkZ: Int) {
            // Set chunk blocks
            for (x in 0 until Chunk.CHUNK_SIZE_X) for (z in 0 until Chunk.CHUNK_SIZE_Z) {
                for (y in 0..39) {
                    batch.setBlock(x, y, z, Block.STONE)
                }
            }
        }

        override fun fillBiomes(biomes: Array<Biome>, chunkX: Int, chunkZ: Int) {
            Arrays.fill(biomes, Biome.PLAINS)
        }

        override fun getPopulators(): List<ChunkPopulator>? {
            return null
        }
    }
}