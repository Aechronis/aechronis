package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.instance.block.Block
import kotlin.test.Test
import kotlin.test.assertEquals

class FallDamageTest : ManagerTest() {
    @Test
    fun `fall damage starts at four full blocks`() {
        val safePlayer = VanillaTest.createPlayer(Pos(14.5, 40.0, 4.5))
        FallDamageListener.onMove(PlayerMoveEvent(safePlayer, Pos(14.5, 43.9, 4.5), false))
        FallDamageListener.onMove(PlayerMoveEvent(safePlayer, Pos(14.5, 40.0, 4.5), true))
        assertEquals(20f, safePlayer.health)
        VanillaTest.remove(safePlayer)

        val damagedPlayer = VanillaTest.createPlayer(Pos(16.5, 40.0, 4.5))
        FallDamageListener.onMove(PlayerMoveEvent(damagedPlayer, Pos(16.5, 44.0, 4.5), false))
        FallDamageListener.onMove(PlayerMoveEvent(damagedPlayer, Pos(16.5, 40.0, 4.5), true))
        assertEquals(19f, damagedPlayer.health)
        VanillaTest.remove(damagedPlayer)
    }

    @Test
    fun `water at the feet clears fall damage`() {
        val player = VanillaTest.createPlayer(Pos(18.5, 40.0, 4.5))
        FallDamageListener.onMove(PlayerMoveEvent(player, Pos(18.5, 45.0, 4.5), false))
        VanillaTest.instance.setBlock(18, 40, 4, Block.WATER)
        FallDamageListener.onMove(PlayerMoveEvent(player, Pos(18.5, 40.0, 4.5), true))
        VanillaTest.instance.setBlock(18, 40, 4, Block.AIR)
        FallDamageListener.onMove(PlayerMoveEvent(player, Pos(18.5, 40.0, 4.5), true))
        assertEquals(20f, player.health)
        VanillaTest.remove(player)
    }
}
