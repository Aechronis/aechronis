package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.listeners.FallDamageListener
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.instance.block.Block
import kotlin.test.Test
import kotlin.test.assertEquals

class ElevatorTest : ManagerTest() {
    @Test
    fun `jumping on an iron floor moves to the next iron floor`() {
        val player = VanillaTest.createPlayer(Pos(108.5, 101.0, 4.5))
        VanillaTest.instance.setBlock(108, 100, 4, Block.IRON_BLOCK)
        VanillaTest.instance.setBlock(108, 105, 4, Block.IRON_BLOCK)

        pressJump(player)

        assertEquals(106.0, player.position.y)
        VanillaTest.instance.setBlock(108, 100, 4, Block.AIR)
        VanillaTest.instance.setBlock(108, 105, 4, Block.AIR)
        VanillaTest.remove(player)
    }

    @Test
    fun `rapid downward elevator use clears stale fall distance`() {
        val player = VanillaTest.createPlayer(Pos(112.5, 110.0, 4.5))
        VanillaTest.instance.setBlock(112, 100, 4, Block.IRON_BLOCK)
        VanillaTest.instance.setBlock(112, 105, 4, Block.IRON_BLOCK)

        move(player, Pos(112.5, 108.0, 4.5), false)
        move(player, Pos(112.5, 106.0, 4.5), false)
        pressShift(player)
        move(player, Pos(112.51, 101.0, 4.5), true)

        assertEquals(101.0, player.position.y)
        assertEquals(20f, player.health)
        VanillaTest.instance.setBlock(112, 100, 4, Block.AIR)
        VanillaTest.instance.setBlock(112, 105, 4, Block.AIR)
        VanillaTest.remove(player)
    }

    private fun pressJump(player: Player) {
        player.inputs().refresh(false, false, false, false, true, false, false)
        Elevator.onInput(PlayerInputEvent(player, false, false, false, false, false, false, false))
    }

    private fun pressShift(player: Player) {
        player.inputs().refresh(false, false, false, false, false, true, false)
        Elevator.onInput(PlayerInputEvent(player, false, false, false, false, false, false, false))
    }

    private fun move(
        player: Player,
        position: Pos,
        onGround: Boolean,
    ) {
        FallDamageListener.onMove(PlayerMoveEvent(player, position, onGround))
        player.refreshPosition(position)
        player.refreshOnGround(onGround)
    }
}
