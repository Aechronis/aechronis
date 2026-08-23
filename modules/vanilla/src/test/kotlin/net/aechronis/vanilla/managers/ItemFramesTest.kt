package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.PlayerHand
import net.minestom.server.entity.metadata.other.ItemFrameMeta
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ItemFramesTest : ManagerTest() {
    @Test
    fun `item frame places stores an item and breaks`() {
        val player = VanillaTest.createPlayer(Pos(30.0, 65.0, 30.0))
        val support = BlockVec(30, 64, 30)
        VanillaTest.instance.setBlock(support, Block.STONE)
        player.itemInMainHand = ItemStack.of(Material.ITEM_FRAME)

        EventDispatcher.call(
            PlayerUseItemOnBlockEvent(
                player,
                PlayerHand.MAIN,
                player.itemInMainHand,
                support,
                Vec(0.5, 0.5, 0.5),
                BlockFace.NORTH,
            ),
        )

        val frame = VanillaTest.instance.entities.firstOrNull { it.entityType == EntityType.ITEM_FRAME }
        assertNotNull(frame)
        assertEquals(ItemStack.AIR, player.itemInMainHand)
        assertTrue(
            VanillaTest.instance
                .getBlock(support)
                .nbtOrEmpty()
                .contains("aechronis:item_frames"),
        )

        val displayed = ItemStack.of(Material.DIAMOND)
        player.itemInMainHand = displayed
        EventDispatcher.call(PlayerEntityInteractEvent(player, frame, PlayerHand.MAIN, Vec.ZERO))
        val meta = frame.entityMeta as ItemFrameMeta
        assertEquals(displayed, meta.item)
        assertEquals(ItemStack.AIR, player.itemInMainHand)

        EventDispatcher.call(EntityAttackEvent(player, frame))
        assertTrue(frame.isRemoved)
        assertFalse(
            VanillaTest.instance
                .getBlock(support)
                .nbtOrEmpty()
                .contains("aechronis:item_frames"),
        )
        VanillaTest.remove(player)
    }
}
