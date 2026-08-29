package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.event.item.EntityEquipEvent
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent
import net.minestom.server.event.player.PlayerTickEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.item.component.EnchantmentList
import net.minestom.server.item.enchant.Enchantment
import net.minestom.server.utils.block.BlockBreakCalculation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EfficiencyTest : ManagerTest() {
    @Test
    fun `efficiency applies the vanilla mining efficiency formula`() {
        val player = VanillaTest.createPlayer(Pos(0.5, 42.0, 0.5))
        player.refreshOnGround(true)
        val unenchantedTicks = BlockBreakCalculation.breakTicks(Block.STONE, player)

        val pickaxe = pickaxeWithEfficiency(3)
        player.itemInMainHand = pickaxe
        Efficiency.onEquip(EntityEquipEvent(player, pickaxe, EquipmentSlot.MAIN_HAND))

        assertEquals(10.0, player.getAttributeValue(Attribute.MINING_EFFICIENCY))
        assertTrue(BlockBreakCalculation.breakTicks(Block.STONE, player) < unenchantedTicks)
        VanillaTest.remove(player)
    }

    @Test
    fun `changing held slots replaces and removes efficiency`() {
        val player = VanillaTest.createPlayer(Pos(2.5, 42.0, 0.5))
        player.inventory.setItemStack(1, pickaxeWithEfficiency(5))

        Efficiency.onHeldSlotChange(PlayerChangeHeldSlotEvent(player, 0, 1))
        assertEquals(26.0, player.getAttributeValue(Attribute.MINING_EFFICIENCY))

        Efficiency.onHeldSlotChange(PlayerChangeHeldSlotEvent(player, 1, 0))
        assertEquals(0.0, player.getAttributeValue(Attribute.MINING_EFFICIENCY))
        VanillaTest.remove(player)
    }

    @Test
    fun `tick reconciles programmatic held slot changes`() {
        val player = VanillaTest.createPlayer(Pos(4.5, 42.0, 0.5))
        player.inventory.setItemStack(1, pickaxeWithEfficiency(1))
        player.setHeldItemSlot(1)

        Efficiency.onTick(PlayerTickEvent(player))

        assertEquals(2.0, player.getAttributeValue(Attribute.MINING_EFFICIENCY))

        player.setHeldItemSlot(0)
        Efficiency.onTick(PlayerTickEvent(player))
        assertEquals(0.0, player.getAttributeValue(Attribute.MINING_EFFICIENCY))
        VanillaTest.remove(player)
    }

    private fun pickaxeWithEfficiency(level: Int): ItemStack =
        ItemStack
            .of(Material.DIAMOND_PICKAXE)
            .with(DataComponents.ENCHANTMENTS, EnchantmentList(Enchantment.EFFICIENCY, level))
}
