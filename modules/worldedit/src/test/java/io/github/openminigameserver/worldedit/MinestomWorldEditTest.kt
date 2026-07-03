package io.github.openminigameserver.worldedit

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.session.ClipboardHolder
import io.github.openminigameserver.worldedit.platform.adapters.MinestomAdapter
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerChatEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.event.server.ServerTickMonitorEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.math.floor
import kotlin.math.min

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinestomWorldEditTest {
    private lateinit var clipboard: Clipboard

    @BeforeAll
    fun testInit() {
        val minecraftServer = MinecraftServer.init(Auth.Online())
        val instanceManager = MinecraftServer.getInstanceManager()
        val worldEdit = MinestomWorldEdit()
        worldEdit.init()

        val eventNode = EventNode.all("worldedit-test-node").setPriority(0)
        MinecraftServer.getGlobalEventHandler().addChild(eventNode)

        tickBar(eventNode)

        eventNode.addListener(PlayerChatEvent::class.java) { event ->
            val instance = event.player.instance
            val asWorld = MinestomAdapter.asWorld(instance)
            WorldEdit.getInstance().newEditSessionBuilder().world(asWorld).build().use { editSession ->
                val operation =
                    ClipboardHolder(clipboard)
                        .createPaste(editSession)
                        .to(MinestomAdapter.asBlockVector(event.player.position))
                        .build()
                Operations.complete(operation)
            }

            event.player.apply {
                setGameMode(GameMode.CREATIVE)
                inventory.addItemStack(ItemStack.of(Material.WOODEN_AXE, 1))
            }
        }

        eventNode.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            val instanceContainer = instanceManager.createInstanceContainer()
            instanceContainer.setGenerator(TestGenerator())

            val player = event.player
            player.permissionLevel = 3
            event.spawningInstance = instanceContainer
            player.respawnPoint = Pos(0.0, 42.0, 0.0)
        }

        minecraftServer.start("0.0.0.0", 25565)
    }

    private fun tickBar(eventNode: EventNode<Event>) {
        val bossBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS)

        eventNode.addListener(PlayerSpawnEvent::class.java) { event ->
            event.player.showBossBar(bossBar)
            event.player.setGameMode(GameMode.CREATIVE)
            event.player.isFlying = true
        }

        eventNode.addListener(ServerTickMonitorEvent::class.java) { e ->
            val tickTime = floor(e.tickMonitor.tickTime * 100.0) / 100.0
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMemory = runtime.maxMemory() / 1024 / 1024

            bossBar.name(Component.text("MSPT: $tickTime | Mem: ${usedMemory}MB/${maxMemory}MB"))
            bossBar.progress(min(tickTime / MinecraftServer.TICK_MS, 1.0).toFloat())
            bossBar.color(if (tickTime > MinecraftServer.TICK_MS) BossBar.Color.RED else BossBar.Color.GREEN)
        }
    }

    @Test
    fun `placeholder test`() {
        assertTrue(true)
    }

    @AfterAll
    fun keepRunning() {
        if (System.getProperty("keepRunning") == "true") {
            Thread.currentThread().join()
        }
    }
}
