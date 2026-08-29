package net.aechronis.combat.tasks

import net.aechronis.combat.CombatTestServer
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelManagerTest {
    private lateinit var connection: TestConnection
    private lateinit var player: Player

    @BeforeAll
    fun setUp() {
        CombatTestServer.initialize()
        connection = TestConnection()
        player = Player(connection, GameProfile(UUID.randomUUID(), "TestPlayer"))
    }

    @AfterAll
    fun tearDown() {
        ModelManager.clearPlayer(player)
        HasteEffectManager.clearPlayer(player)
    }

    @AfterEach
    fun resetPlayer() {
        ModelManager.clearPlayer(player)
        HasteEffectManager.clearPlayer(player)
    }

    @Test
    fun `hit animation state changes only on transitions`() {
        val originalAttackSpeed = player.getAttribute(Attribute.ATTACK_SPEED).getValue()
        val originalBlockBreakSpeed = player.getAttribute(Attribute.BLOCK_BREAK_SPEED).getValue()

        ModelManager.setHitAnimationDisabled(player, true)
        assertTrue(player.getAttribute(Attribute.ATTACK_SPEED).getValue() > originalAttackSpeed)
        assertEquals(0.0, player.getAttribute(Attribute.BLOCK_BREAK_SPEED).getValue())
        assertEquals(10, player.getEffect(PotionEffect.HASTE)?.potion()?.amplifier())

        connection.sentPackets.clear()
        ModelManager.setHitAnimationDisabled(player, true)
        assertTrue(connection.sentPackets.isEmpty())

        ModelManager.setHitAnimationDisabled(player, false)
        assertEquals(originalAttackSpeed, player.getAttribute(Attribute.ATTACK_SPEED).getValue())
        assertEquals(originalBlockBreakSpeed, player.getAttribute(Attribute.BLOCK_BREAK_SPEED).getValue())
        assertFalse(player.hasEffect(PotionEffect.HASTE))
    }

    @Test
    fun `stronger haste wins without being refreshed by weaker updates`() {
        HasteEffectManager.set(player, "nodes:test", amplifier = 1, durationTicks = 200)
        assertEquals(1, player.getEffect(PotionEffect.HASTE)?.potion()?.amplifier())

        HasteEffectManager.set(
            player,
            "combat:test",
            amplifier = 10,
            durationTicks = Potion.INFINITE_DURATION,
        )
        assertEquals(10, player.getEffect(PotionEffect.HASTE)?.potion()?.amplifier())

        connection.sentPackets.clear()
        HasteEffectManager.set(player, "nodes:test", amplifier = 1, durationTicks = 180)

        assertTrue(connection.sentPackets.isEmpty())
        assertEquals(10, player.getEffect(PotionEffect.HASTE)?.potion()?.amplifier())

        HasteEffectManager.clear(player, "combat:test")
        val restored = player.getEffect(PotionEffect.HASTE)?.potion()
        assertEquals(1, restored?.amplifier())
        assertEquals(180, restored?.duration())

        HasteEffectManager.clear(player, "nodes:test")
        assertFalse(player.hasEffect(PotionEffect.HASTE))
    }

    private class TestConnection : PlayerConnection() {
        val sentPackets = mutableListOf<SendablePacket>()

        override fun sendPacket(packet: SendablePacket) {
            sentPackets += packet
        }

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }
}
