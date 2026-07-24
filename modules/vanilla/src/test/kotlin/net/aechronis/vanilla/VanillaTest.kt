package net.aechronis.vanilla

import net.aechronis.utils.createTestServer
import net.aechronis.vanilla.managers.EnvironmentalDamage
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VanillaTest {
    private lateinit var instance: InstanceContainer

    @BeforeAll
    fun testInit() {
        instance = createTestServer()

        // init main
        Vanilla.init()
    }

    @Test
    fun `fire damages immediately then every ten ticks`() {
        val player = createPlayer(Pos(0.5, 40.0, 0.5))
        instance.setBlock(0, 40, 0, Block.FIRE)

        EnvironmentalDamage.tickPlayer(player)
        repeat(10) { EnvironmentalDamage.tickPlayer(player) }

        assertEquals(18f, player.health)
        assertEquals(160, player.fireTicks)
        instance.setBlock(0, 40, 0, Block.AIR)
    }

    @Test
    fun `drowning damages after vanilla air supply expires`() {
        val player = createPlayer(Pos(2.5, 40.0, 2.5))
        instance.setBlock(2, 41, 2, Block.WATER)

        repeat(320) { EnvironmentalDamage.tickPlayer(player) }

        assertEquals(18f, player.health)
        assertEquals(0, player.entityMeta.airTicks)
        instance.setBlock(2, 41, 2, Block.AIR)
    }

    @Test
    fun `fire resistance and creative mode prevent environmental damage`() {
        val fireResistant = createPlayer(Pos(4.5, 40.0, 4.5))
        instance.setBlock(4, 40, 4, Block.FIRE)
        fireResistant.addEffect(Potion(PotionEffect.FIRE_RESISTANCE, 0, 100))

        EnvironmentalDamage.tickPlayer(fireResistant)

        assertEquals(20f, fireResistant.health)
        assertEquals(160, fireResistant.fireTicks)

        val creative = createPlayer(Pos(6.5, 40.0, 6.5))
        instance.setBlock(6, 40, 6, Block.FIRE)
        creative.entityMeta.airTicks = 10
        creative.setGameMode(GameMode.CREATIVE)

        EnvironmentalDamage.tickPlayer(creative)

        assertEquals(20f, creative.health)
        assertEquals(0, creative.fireTicks)
        assertEquals(300, creative.entityMeta.airTicks)
        instance.setBlock(4, 40, 4, Block.AIR)
        instance.setBlock(6, 40, 6, Block.AIR)
    }

    private fun createPlayer(position: Pos): Player {
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "test"))
        player.setInstance(instance, position).join()
        player.health = 20f
        return player
    }

    private class TestConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = Unit

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }

    @AfterAll
    fun keepRunning() {
        // if -DkeepRunning=true is set keep server running for manual testing
        if (System.getProperty("keepRunning") == "true") {
            Thread.currentThread().join()
        }
    }
}
