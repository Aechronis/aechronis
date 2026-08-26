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
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket
import net.minestom.server.network.packet.server.play.SpawnEntityPacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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
    fun `restoring visibility adds player info before the entity spawn`() {
        val viewerConnection = TestConnection()
        val targetConnection = TestConnection()
        val viewer = Player(viewerConnection, GameProfile(UUID.randomUUID(), "viewer"))
        val target = Player(targetConnection, GameProfile(UUID.randomUUID(), "target"))
        viewer.setInstance(instance, Pos(0.5, 40.0, 0.5)).join()
        target.setInstance(instance, Pos(2.5, 40.0, 0.5)).join()

        try {
            VisibilityRules.applyForViewers(target, { false }, listOf(viewer))
            viewerConnection.packets.clear()

            VisibilityRules.applyForViewers(target, null, listOf(viewer))

            val profileAdd =
                viewerConnection.packets.indexOfFirst { packet ->
                    packet is PlayerInfoUpdatePacket &&
                        PlayerInfoUpdatePacket.Action.ADD_PLAYER in packet.actions &&
                        packet.entries.any { it.uuid == target.uuid }
                }
            val spawn =
                viewerConnection.packets.indexOfFirst { packet ->
                    packet is SpawnEntityPacket && packet.entityId == target.entityId
                }
            assertTrue(profileAdd >= 0)
            assertTrue(spawn > profileAdd)
        } finally {
            target.remove()
            viewer.remove()
        }
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
        val packets = CopyOnWriteArrayList<SendablePacket>()

        override fun sendPacket(packet: SendablePacket) {
            packets += packet
        }

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }
}
