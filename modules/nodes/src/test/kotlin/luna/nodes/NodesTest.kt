package luna.nodes

import luna.nodes.objects.TerritoryId
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Paths
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

        MinecraftServer.getGlobalEventHandler().addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            val player = event.player
            event.spawningInstance = instance
            player.respawnPoint = Pos(27000.0, 60.0, 5700.0)
            player.gameMode = GameMode.CREATIVE
        }

        // create test config
        val config = NodesConfig(
            save = false,
            pathPlugin = Paths.get(javaClass.getResource("/nodes/world.json")!!.toURI()).parent.toString(),
            incomePeriod = 100,
            canCreateTownDuringWar = true,
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
        Nodes.enableWar(true, false, true)
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
