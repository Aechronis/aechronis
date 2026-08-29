package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.Player
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class OresTest : ManagerTest() {
    @Test
    fun `malformed cooldown snapshot fails restoration`() {
        assertFailsWith<Exception> { Ores.restoreTransientState(byteArrayOf(1), onlinePlayers = emptyList()) }
    }

    @Test
    fun `shutdown replaces a player's fake bedrock with the real block`() {
        val connection = PacketConnection()
        val player = Player(connection, GameProfile(UUID.randomUUID(), "ore-handoff-test"))
        val position = BlockVec(103, 40, 12)
        VanillaTest.instance.setBlock(position, Block.DIAMOND_ORE)
        player.setInstance(VanillaTest.instance, position.asPos()).get(10, TimeUnit.SECONDS)
        val location =
            Ores.OreLocation(
                VanillaTest.instance.getDimensionName(),
                position.blockX(),
                position.blockY(),
                position.blockZ(),
            )
        val cooldown = Ores.Cooldown(player.uuid, location)

        try {
            Ores.cooldowns[cooldown] = Long.MAX_VALUE
            connection.packets.clear()

            Ores.shutdown(listOf(player))

            val packet = connection.packets.filterIsInstance<BlockChangePacket>().single()
            assertEquals(Block.DIAMOND_ORE.stateId(), packet.blockStateId())
            assertFalse(Ores.cooldowns.containsKey(cooldown))
        } finally {
            Ores.cooldowns.remove(cooldown)
            player.remove()
            VanillaTest.instance.setBlock(position, Block.AIR)
        }
    }

    @Test
    fun `active cooldown survives a module generation handoff but expired cooldown does not`() {
        val playerId = UUID.randomUUID()
        val location = Ores.OreLocation("minecraft:overworld", 101, 40, 12)
        val cooldown = Ores.Cooldown(playerId, location)
        val now = 1_000_000L

        try {
            Ores.ores[location] = Ores.Ore(timeSeconds = 30)
            Ores.cooldowns[cooldown] = now + 30_000L
            val snapshot = Ores.captureTransientState()
            Ores.cooldowns.clear()

            Ores.restoreTransientState(snapshot, now, emptyList())
            assertEquals(now + 30_000L, Ores.cooldowns[cooldown])

            Ores.restoreTransientState(snapshot, now + 30_001L, emptyList())
            assertFalse(Ores.cooldowns.containsKey(cooldown))
        } finally {
            Ores.cooldowns.remove(cooldown)
            Ores.ores.remove(location)
        }
    }

    private class PacketConnection : PlayerConnection() {
        val packets = mutableListOf<SendablePacket>()

        override fun sendPacket(packet: SendablePacket) {
            packets += packet
        }

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }
}
