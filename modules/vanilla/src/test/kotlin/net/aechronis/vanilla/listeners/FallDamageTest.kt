package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.item.component.EnchantmentList
import net.minestom.server.item.enchant.Enchantment
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class FallDamageTest : ManagerTest() {
    @Test
    fun `fall damage starts at four full blocks`() {
        val safePlayer = playerAbovePlatform(14, 64.999)
        move(safePlayer, Pos(14.5, 61.0, 4.5), true)
        assertEquals(20f, safePlayer.health)
        VanillaTest.remove(safePlayer)

        val damagedPlayer = playerAbovePlatform(16, 65.0)
        move(damagedPlayer, Pos(16.5, 61.0, 4.5), true)
        assertEquals(19f, damagedPlayer.health)
        VanillaTest.remove(damagedPlayer)
    }

    @Test
    fun `separate downward movements accumulate like vanilla`() {
        val player = playerAbovePlatform(18, 66.0)
        move(player, Pos(18.5, 64.0, 4.5), false)
        move(player, Pos(18.5, 62.0, 4.5), false)
        move(player, Pos(18.5, 61.0, 4.5), true)

        assertEquals(18f, player.health)
        VanillaTest.remove(player)
    }

    @Test
    fun `water touching the player clears fall damage`() {
        val player = playerAbovePlatform(20, 67.0)
        move(player, Pos(20.5, 64.0, 4.5), false)
        VanillaTest.instance.setBlock(20, 62, 4, Block.WATER)
        move(player, Pos(20.5, 62.0, 4.5), false)
        VanillaTest.instance.setBlock(20, 62, 4, Block.AIR)
        move(player, Pos(20.5, 61.0, 4.5), true)

        assertEquals(20f, player.health)
        VanillaTest.remove(player)
    }

    @Test
    fun `landing while centered over air is supported by the full player footprint`() {
        VanillaTest.instance.setBlock(24, 69, 4, Block.STONE)
        VanillaTest.instance.setBlock(28, 60, 4, Block.STONE)
        val player = VanillaTest.createPlayer(Pos(24.5, 72.9, 4.5))

        move(player, Pos(24.5, 72.9, 4.5), false)
        move(player, Pos(25.1, 70.0, 4.5), true)

        player.teleport(Pos(28.5, 61.0, 4.5)).get(10, TimeUnit.SECONDS)
        move(player, Pos(28.51, 61.0, 4.5), true)

        assertEquals(20f, player.health)
        VanillaTest.remove(player)
    }

    @Test
    fun `rapid grounded teleports do not become fall distance`() {
        VanillaTest.instance.setBlock(32, 69, 4, Block.STONE)
        VanillaTest.instance.setBlock(32, 60, 4, Block.STONE)
        val player = VanillaTest.createPlayer(Pos(32.5, 70.0, 4.5))

        // A stale airborne flag while still supported must be cleared at the teleport source.
        move(player, Pos(32.5, 70.0, 4.5), false)
        player.teleport(Pos(32.5, 61.0, 4.5)).get(10, TimeUnit.SECONDS)
        player.teleport(Pos(32.5, 61.0, 4.5)).get(10, TimeUnit.SECONDS)
        move(player, Pos(32.51, 61.0, 4.5), true)

        assertEquals(20f, player.health)
        VanillaTest.remove(player)
    }

    @Test
    fun `midair teleport preserves the real fall but not teleport displacement`() {
        VanillaTest.instance.setBlock(36, 60, 4, Block.STONE)
        val player = VanillaTest.createPlayer(Pos(36.5, 70.0, 4.5))

        move(player, Pos(36.5, 68.0, 4.5), false)
        player.teleport(Pos(36.5, 66.0, 4.5)).get(10, TimeUnit.SECONDS)
        move(player, Pos(36.5, 61.0, 4.5), true)

        // Only the two actual movements (2 + 5), not the two-block teleport, are counted.
        assertEquals(16f, player.health)
        VanillaTest.remove(player)
    }

    @Test
    fun `feather falling reduces fall damage through vanilla protection`() {
        val player = playerAbovePlatform(38, 69.0)
        player.setEquipment(
            EquipmentSlot.BOOTS,
            ItemStack
                .of(Material.IRON_BOOTS)
                .with(DataComponents.ENCHANTMENTS, EnchantmentList(Enchantment.FEATHER_FALLING, 4)),
        )

        move(player, Pos(38.5, 61.0, 4.5), true)

        assertEquals(17.4f, player.health, 0.0001f)
        VanillaTest.remove(player)
    }

    @Test
    fun `vanilla landing block multipliers are applied`() {
        val hayPlayer = playerAbovePlatform(40, 69.0, Block.HAY_BLOCK)
        move(hayPlayer, Pos(40.5, 61.0, 4.5), true)
        assertEquals(19f, hayPlayer.health)
        VanillaTest.remove(hayPlayer)

        val bedPlayer = playerAbovePlatform(42, 68.5625, Block.WHITE_BED)
        move(bedPlayer, Pos(42.5, 60.5625, 4.5), true)
        assertEquals(18f, bedPlayer.health)
        VanillaTest.remove(bedPlayer)

        val slimePlayer = playerAbovePlatform(44, 75.0, Block.SLIME_BLOCK)
        move(slimePlayer, Pos(44.5, 61.0, 4.5), true)
        assertEquals(20f, slimePlayer.health)
        VanillaTest.remove(slimePlayer)
    }

    private fun playerAbovePlatform(
        x: Int,
        y: Double,
        block: Block = Block.STONE,
    ): Player {
        VanillaTest.instance.setBlock(x, 60, 4, block)
        return VanillaTest.createPlayer(Pos(x + 0.5, y, 4.5))
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
