package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.player.PlayerChunkLoadEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.instance.block.BlockHandler
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.test.Test
import kotlin.test.assertEquals

class ShelvesTest : ManagerTest() {
    @Test
    fun `shelf swaps the clicked slot with the main hand`() {
        val player = VanillaTest.createPlayer(Pos(20.0, 65.0, 20.0))
        val position = BlockVec(20, 64, 20)
        val handler = MinecraftServer.getBlockManager().getHandler(Block.OAK_SHELF.key().asString())!!
        VanillaTest.instance.setBlock(position, Block.OAK_SHELF.withHandler(handler))

        val first = ItemStack.of(Material.DIAMOND, 3)
        player.itemInMainHand = first
        interact(handler, player, position, VanillaTest.instance.getBlock(position))
        assertEquals(ItemStack.AIR, player.itemInMainHand)

        val second = ItemStack.of(Material.GOLD_INGOT, 2)
        player.itemInMainHand = second
        interact(handler, player, position, VanillaTest.instance.getBlock(position))
        assertEquals(first, player.itemInMainHand)
        VanillaTest.remove(player)
    }

    @Test
    fun `player chunk load restores a persisted shelf handler`() {
        val player = VanillaTest.createPlayer(Pos(52.0, 65.0, 52.0))
        val position = BlockVec(52, 64, 52)
        VanillaTest.instance.setBlock(position, Block.OAK_SHELF)

        EventDispatcher.call(PlayerChunkLoadEvent(player, position.chunkX(), position.chunkZ()))

        val restored = VanillaTest.instance.getBlock(position)
        assertEquals(Block.OAK_SHELF.key(), restored.handler()?.key)
        VanillaTest.remove(player)
    }

    private fun interact(
        handler: BlockHandler,
        player: net.minestom.server.entity.Player,
        position: BlockVec,
        block: Block,
    ) {
        handler.onInteract(
            BlockHandler.Interaction(
                block,
                VanillaTest.instance,
                BlockFace.NORTH,
                position,
                Vec(0.1, 0.5, 0.5),
                player,
                PlayerHand.MAIN,
            ),
        )
    }
}
