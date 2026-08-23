package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.StringBinaryTag
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.player.PlayerEditSignEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.instance.block.BlockHandler
import net.minestom.server.instance.block.rule.BlockPlacementRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SignsTest : ManagerTest() {
    @Test
    fun `sign edits are written to front text nbt`() {
        val player =
            VanillaTest.createPlayer(
                Pos(4.0, 65.0, 4.0),
            )
        val position = BlockVec(4, 64, 4)
        val handler = MinecraftServer.getBlockManager().getHandler(Block.OAK_SIGN.key().asString())!!
        val placed = Block.OAK_SIGN.withHandler(handler)
        VanillaTest.instance.setBlock(position, placed)

        handler.onPlace(
            BlockHandler.PlayerPlacement(
                placed,
                Block.AIR,
                VanillaTest.instance,
                position,
                player,
                PlayerHand.MAIN,
                BlockFace.TOP,
                0.5f,
                0.5f,
                0.5f,
            ),
        )
        EventDispatcher.call(
            PlayerEditSignEvent(
                player,
                VanillaTest.instance,
                VanillaTest.instance.getBlock(position),
                position,
                listOf("hello", "", "", ""),
                true,
            ),
        )

        val front =
            VanillaTest.instance
                .getBlock(position)
                .nbtOrEmpty()
                .getCompound("front_text")
        val lines = front.getList("messages", BinaryTagTypes.STRING)
        assertEquals("hello", (lines[0] as StringBinaryTag).value())
        VanillaTest.remove(player)
    }

    @Test
    fun `hanging sign placement produces a wall hanging sign`() {
        val position = BlockVec(12, 64, 12)
        val rule = MinecraftServer.getBlockManager().getBlockPlacementRule(Block.OAK_HANGING_SIGN)
        assertNotNull(rule)

        val placed =
            rule.blockPlace(
                BlockPlacementRule.PlacementState(
                    VanillaTest.instance,
                    Block.OAK_HANGING_SIGN,
                    BlockFace.NORTH,
                    position,
                    Vec(0.5, 0.5, 0.5),
                    null,
                    null,
                    false,
                ),
            )
        assertEquals(Block.OAK_WALL_HANGING_SIGN.key(), placed?.key())
        assertEquals("north", placed?.getProperty("facing"))
    }
}
