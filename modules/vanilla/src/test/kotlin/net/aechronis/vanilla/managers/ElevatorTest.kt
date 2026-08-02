package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.instance.block.Block
import kotlin.test.Test
import kotlin.test.assertEquals

class ElevatorTest : ManagerTest() {
    @Test
    fun `jumping on an iron floor moves to the next iron floor`() {
        val player = VanillaTest.createPlayer(Pos(108.5, 101.0, 4.5))
        VanillaTest.instance.setBlock(108, 100, 4, Block.IRON_BLOCK)
        VanillaTest.instance.setBlock(108, 105, 4, Block.IRON_BLOCK)

        player.inputs().refresh(false, false, false, false, true, false, false)
        Elevator.onInput(PlayerInputEvent(player, false, false, false, false, false, false, false))

        assertEquals(106.0, player.position.y)
        VanillaTest.instance.setBlock(108, 100, 4, Block.AIR)
        VanillaTest.instance.setBlock(108, 105, 4, Block.AIR)
        VanillaTest.remove(player)
    }
}
