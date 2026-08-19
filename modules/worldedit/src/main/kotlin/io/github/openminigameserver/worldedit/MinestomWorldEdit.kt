package io.github.openminigameserver.worldedit

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.event.platform.PlatformReadyEvent
import com.sk89q.worldedit.event.platform.PlatformsRegisteredEvent
import com.sk89q.worldedit.internal.block.BlockStateIdAccess
import com.sk89q.worldedit.world.block.BlockType
import com.sk89q.worldedit.world.item.ItemType
import io.github.openminigameserver.worldedit.platform.MinestomBlockRegistry
import io.github.openminigameserver.worldedit.platform.MinestomPlatform
import io.github.openminigameserver.worldedit.platform.adapters.MinestomAdapter
import io.github.openminigameserver.worldedit.platform.config.WorldEditConfig
import io.github.openminigameserver.worldedit.platform.config.WorldEditConfiguration
import io.github.openminigameserver.worldedit.platform.misc.WorldEditExecutor
import net.minestom.server.instance.block.Block
import net.minestom.server.item.Material

class MinestomWorldEdit {
    private val platform = MinestomPlatform(this)

    lateinit var config: WorldEditConfiguration

    fun init(config: WorldEditConfig = WorldEditConfig()) {
        config.dataFolder.mkdirs()
        MinestomAdapter.platform = platform
        this.config = WorldEditConfiguration(config).apply { load() }

        platform.registerWorldEditEventHandlers()
        WorldEdit.getInstance().platformManager.register(platform)
        WorldEdit.getInstance().eventBus.post(PlatformsRegisteredEvent())

        registerBlocks()
        registerItems()

        WorldEdit.getInstance().eventBus.post(PlatformReadyEvent(platform))
        println("Finished loading WorldEdit")
    }

    private fun registerItems() {
        println("Registering items with WorldEdit")
        for (itemType in Material.values()) {
            val id: String = itemType.key().asString()
            if (!ItemType.REGISTRY.keySet().contains(id)) {
                ItemType.REGISTRY.register(id, ItemType(id))
            }
        }
    }

    private fun registerBlocks() {
        println("Registering blocks with WorldEdit")
        Block.values().forEach { minestomBlock ->
            try {
                val id = minestomBlock.key().asString()
                val blockType =
                    BlockType.REGISTRY[id]
                        ?: BlockType(id).also { BlockType.REGISTRY.register(id, it) }

                // WorldEdit's state id is normally supplied by the platform. Register every
                // Minestom state, not only blocks without properties. Schematics contain state
                // ids for the property-bearing blocks as well.
                minestomBlock.possibleStates().forEach { nativeState ->
                    val state = MinestomBlockRegistry.getWorldEditBlockState(nativeState) ?: return@forEach
                    val existing = BlockStateIdAccess.getBlockStateById(nativeState.stateId())
                    if (existing == null || existing === state) {
                        BlockStateIdAccess.register(state, nativeState.stateId())
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun shutdown() {
        val worldEdit = WorldEdit.getInstance()
        platform.unregisterWorldEditEventHandlers()
        worldEdit.sessionManager.unload()
        worldEdit.platformManager.unregister(platform)
        WorldEditExecutor.shutdown()
    }
}
