package net.aechronis.utils

import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.generator.Generator
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestServerTest {
    private val spawnPoint = Pos(12.0, 34.0, 56.0)
    private lateinit var instance: InstanceContainer

    @BeforeAll
    fun setUp() {
        val generator =
            Generator { unit ->
                unit.modifier().fillHeight(0, 60, Block.DIAMOND_BLOCK)
            }

        instance =
            createTestServer(
                generator = generator,
                gameMode = GameMode.ADVENTURE,
                spawnPoint = spawnPoint,
                address = "127.0.0.1",
                port = 0,
                auth = Auth.Offline(),
            )
    }

    @AfterAll
    fun tearDown() {
        MinecraftServer.stopCleanly()
    }

    @Test
    fun `creates a running server and generated instance`() {
        assertTrue(MinecraftServer.isStarted())
        assertTrue(MinecraftServer.getServer().port > 0)

        instance.loadChunk(0, 0).join()

        assertEquals(Block.DIAMOND_BLOCK, instance.getBlock(0, 59, 0))
        assertEquals(Block.AIR, instance.getBlock(0, 60, 0))
    }

    @Test
    fun `configures joining players and tps bar`() {
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "TestPlayer"))
        val configurationEvent = AsyncPlayerConfigurationEvent(player, true)

        val configurationThread =
            Thread.ofVirtual().start {
                MinecraftServer.getGlobalEventHandler().call(configurationEvent)
            }
        configurationThread.join()

        assertSame(instance, configurationEvent.spawningInstance)
        assertEquals(spawnPoint, player.respawnPoint)
        assertEquals(GameMode.ADVENTURE, player.gameMode)

        MinecraftServer.getGlobalEventHandler().call(PlayerSpawnEvent(player, instance, true))
    }

    private class TestConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = Unit

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }
}
