package io.github.openminigameserver.worldedit

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.event.platform.PlatformReadyEvent
import com.sk89q.worldedit.internal.block.BlockStateIdAccess
import com.sk89q.worldedit.world.block.BlockType
import com.sk89q.worldedit.world.item.ItemType
import io.github.openminigameserver.worldedit.platform.MinestomPlatform
import io.github.openminigameserver.worldedit.platform.adapters.MinestomAdapter
import io.github.openminigameserver.worldedit.platform.config.WorldEditConfiguration
import net.minestom.server.MinecraftServer
import net.minestom.server.extensions.Extension
import net.minestom.server.item.Material
import net.minestom.server.registry.Registries
import org.spongepowered.configurate.yaml.NodeStyle
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import java.io.File


class MinestomWorldEdit : Extension() {
    companion object {
        val dataFolder by lazy {
            File(
                MinecraftServer.getExtensionManager().extensionFolder,
                "WorldEdit"
            ).also { it.mkdirs() }
        }
    }

    private val platform = MinestomPlatform(this)
    override fun initialize() {
        MinestomAdapter.platform = platform
        loadConfig()

        WorldEdit.getInstance().platformManager.register(platform)

        registerBlocks()
        registerItems()

        WorldEdit.getInstance().eventBus.post(PlatformReadyEvent())
        logger.info("Finished loading WorldEdit")
    }

    lateinit var config: WorldEditConfiguration
    private fun loadConfig() {
        val file = File(dataFolder, "config.yml")
        config = WorldEditConfiguration(
            YamlConfigurationLoader.builder()
                .file(file)
                .nodeStyle(NodeStyle.BLOCK).build(), logger
        )
        config.load()
    }

    private fun registerItems() {
        logger.info("Registering items with WorldEdit")
        for (itemType in Material.values()) {
            val id: String = itemType.getName()
            if (!ItemType.REGISTRY.keySet().contains(id)) {
                ItemType.REGISTRY.register(id, ItemType(id))
            }
        }
    }

    private fun registerBlocks() {
        logger.info("Registering blocks with WorldEdit")
        Registries.blocks.forEach { (t, it) ->
            try {
                val id: String = t.toString()
                if (!BlockType.REGISTRY.keySet().contains(id)) {
                    val block = BlockType(id)
                    if (it.alternatives.isEmpty()) {
                        val state = block.defaultState
                        BlockStateIdAccess.register(state, it.blockId.toInt())
                    }
                    BlockType.REGISTRY.register(id, block)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun terminate() {
        val worldEdit = WorldEdit.getInstance()
        worldEdit.sessionManager.unload()
        worldEdit.platformManager.unregister(platform)
    }
}