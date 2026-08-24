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
import kotlin.test.assertNull

class SignsTest : ManagerTest() {
    @Test
    fun `sign edits are written to front text nbt`() {
        val player = VanillaTest.createPlayer(Pos(4.0, 65.0, 4.0))
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
    fun `standing signs use vanilla rounded sixteen step rotation`() {
        val player = VanillaTest.createPlayer(Pos(12.0, 65.0, 12.0, 11.25f, 0f))
        val position = BlockVec(12, 64, 12)
        VanillaTest.instance.setBlock(position.relative(BlockFace.BOTTOM), Block.STONE)

        val placed = place(Block.OAK_SIGN, BlockFace.TOP, position, player.position)

        assertEquals(Block.OAK_SIGN.key(), placed?.key())
        assertEquals("9", placed?.getProperty("rotation"))
        VanillaTest.remove(player)
    }

    @Test
    fun `ordinary sign side placement chooses a supported wall sign`() {
        val player = VanillaTest.createPlayer(Pos(28.0, 65.0, 28.0, 0f, 0f))
        val position = BlockVec(28, 64, 28)
        // The clicked block is immediately south of the placement position.
        VanillaTest.instance.setBlock(position.relative(BlockFace.SOUTH), Block.STONE)

        val placed = place(Block.OAK_SIGN, BlockFace.NORTH, position, player.position)

        assertEquals(Block.OAK_WALL_SIGN.key(), placed?.key())
        assertEquals("north", placed?.getProperty("facing"))
        VanillaTest.remove(player)
    }

    @Test
    fun `hanging sign below a full block uses ceiling attachment`() {
        val player = VanillaTest.createPlayer(Pos(44.0, 65.0, 44.0, 0f, 0f))
        val position = BlockVec(44, 64, 44)
        VanillaTest.instance.setBlock(position.relative(BlockFace.TOP), Block.STONE)

        val placed = place(Block.OAK_HANGING_SIGN, BlockFace.BOTTOM, position, player.position)

        assertEquals(Block.OAK_HANGING_SIGN.key(), placed?.key())
        assertEquals("false", placed?.getProperty("attached"))
        assertEquals("0", placed?.getProperty("rotation"))
        VanillaTest.remove(player)
    }

    @Test
    fun `sneaking below a full block uses middle hanging attachment`() {
        val player = VanillaTest.createPlayer(Pos(52.0, 65.0, 52.0, 0f, 0f))
        val position = BlockVec(52, 64, 52)
        VanillaTest.instance.setBlock(position.relative(BlockFace.TOP), Block.STONE)

        val placed = place(Block.OAK_HANGING_SIGN, BlockFace.BOTTOM, position, player.position, sneaking = true)

        assertEquals(Block.OAK_HANGING_SIGN.key(), placed?.key())
        assertEquals("true", placed?.getProperty("attached"))
        assertEquals("8", placed?.getProperty("rotation"))
        VanillaTest.remove(player)
    }

    @Test
    fun `hanging sign can chain below another hanging sign`() {
        val player = VanillaTest.createPlayer(Pos(56.0, 65.0, 56.0, 0f, 0f))
        val position = BlockVec(56, 64, 56)
        VanillaTest.instance.setBlock(position.relative(BlockFace.TOP), Block.OAK_HANGING_SIGN)

        val placed = place(Block.OAK_HANGING_SIGN, BlockFace.BOTTOM, position, player.position)

        assertEquals(Block.OAK_HANGING_SIGN.key(), placed?.key())
        assertEquals("false", placed?.getProperty("attached"))
        VanillaTest.remove(player)
    }

    @Test
    fun `hanging sign side placement is perpendicular and needs endpoint support`() {
        val player = VanillaTest.createPlayer(Pos(60.0, 65.0, 60.0, 0f, 0f))
        val position = BlockVec(60, 64, 60)
        // Clicking a north face places at this position; its south endpoint is the clicked block.
        VanillaTest.instance.setBlock(position.relative(BlockFace.SOUTH), Block.STONE)

        val placed = place(Block.OAK_HANGING_SIGN, BlockFace.NORTH, position, player.position)

        assertEquals(Block.OAK_WALL_HANGING_SIGN.key(), placed?.key())
        assertEquals("east", placed?.getProperty("facing"))
        VanillaTest.remove(player)
    }

    @Test
    fun `unsupported wall hanging sign is rejected`() {
        val player = VanillaTest.createPlayer(Pos(76.0, 65.0, 76.0, 0f, 0f))
        val position = BlockVec(76, 64, 76)

        assertNull(place(Block.OAK_HANGING_SIGN, BlockFace.NORTH, position, player.position))
        VanillaTest.remove(player)
    }

    private fun place(
        block: Block,
        face: BlockFace,
        position: BlockVec,
        playerPosition: Pos,
        sneaking: Boolean = false,
    ): Block? {
        val rule = MinecraftServer.getBlockManager().getBlockPlacementRule(block)
        assertNotNull(rule)
        return rule.blockPlace(
            BlockPlacementRule.PlacementState(
                VanillaTest.instance,
                block,
                face,
                position,
                Vec(0.5, 0.5, 0.5),
                playerPosition,
                null,
                sneaking,
            ),
        )
    }
}
