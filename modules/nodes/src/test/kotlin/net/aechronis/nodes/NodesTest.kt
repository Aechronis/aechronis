package net.aechronis.nodes

import net.aechronis.nodes.objects.TerritoryId
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.event.server.ServerTickMonitorEvent
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.floor
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NodesTest {

    @BeforeAll
    fun setup() {
        // start server
        MinecraftServer.init().start("0.0.0.0", 55555)

        // create instance
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.setGenerator(TestGenerator())

        val eventNode = EventNode.all("test-node").setPriority(0)

        MinecraftServer.getGlobalEventHandler().addChild(eventNode)

        val bossBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS)

        eventNode.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            val player = event.player
            event.spawningInstance = instance
            player.respawnPoint = Pos(27000.0, 60.0, 5700.0)
            player.gameMode = GameMode.CREATIVE
        }

        eventNode.addListener(PlayerSpawnEvent::class.java) { event ->
            event.player.showBossBar(bossBar)
        }

        eventNode.addListener(PlayerBlockInteractEvent::class.java) { event ->
            if (!event.isCancelled) {
                Message.print(event.player, "you would have just interacted")
            } else {
                Message.error(event.player, "interact event cancelled")
            }
        }

        eventNode.addListener(ServerTickMonitorEvent::class.java) { e ->
            val tickTime = floor(e.tickMonitor.tickTime * 100.0) / 100.0
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMemory = runtime.maxMemory() / 1024 / 1024

            bossBar.name(
                Component.text()
                    .append(Component.text("MSPT: $tickTime | Mem: ${usedMemory}MB/${maxMemory}MB")),
            )
            bossBar.progress(min(tickTime / MinecraftServer.TICK_MS, 1.0).toFloat())

            if (tickTime > MinecraftServer.TICK_MS) {
                bossBar.color(BossBar.Color.RED)
            } else {
                bossBar.color(BossBar.Color.GREEN)
            }
        }

        val dir = Paths.get(javaClass.getResource("/nodes/world.json")!!.toURI()).parent
        val tmpDir = Files.createTempDirectory("nodes-test")
        Files.walk(dir).forEach { src ->
            val dest = tmpDir.resolve(dir.relativize(src))
            if (Files.isDirectory(src)) Files.createDirectories(dest)
            else Files.copy(src, dest)
        }

        // create test config
        val config = NodesConfig(
            path = tmpDir.toString(),
        )

        // initialize nodes with test config
        Nodes.initialize(config)
    }

    @Test
    fun `territories are loaded`() {
        assertTrue(Nodes.getTerritoryCount() > 0, "Should have loaded territories")
    }

    @Test
    fun `towns are loaded`() {
        assertTrue(Nodes.getTownCount() > 0, "Should have loaded towns")
    }

    @Test
    fun `can get town by name`() {
        assertNotNull(Nodes.getTownFromName("London"), "Town from test data should not be null")
    }

    @Test
    fun `can create a new town`() {
        // territory without a town
        val territory = Nodes.getTerritoryFromId(TerritoryId(18248))
        assertNotNull(territory, "Territory should exist")

        val result = Nodes.createTown("Birmingham", territory, null)
        assertTrue(result.isSuccess, "Town should have created")

        val town = Nodes.getTownFromName("Birmingham")
        assertNotNull(town)
        assertEquals("Birmingham", town.name)
    }

    @Test
    fun `can enable war`() {
        Nodes.enableWar(canAnnexTerritories = true, canOnlyAttackBorders = false, destructionEnabled = true)
        assertTrue(Nodes.war.enabled, "War should be enabled")
    }

    @AfterAll
    fun keepRunning() {
        // if -DkeepRunning=true is set keep server running for manual testing
        if (System.getProperty("keepRunning") == "true") {
            Thread.currentThread().join()
        }
    }
}
