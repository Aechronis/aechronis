package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.listeners.BundleListener
import net.aechronis.vanilla.objects.Bundle
import net.kyori.adventure.text.Component
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundlesTest : ManagerTest() {
    @Test
    fun `bundle previews and restores hotbar armor offhand and inventory order`() {
        val player = VanillaTest.createPlayer(Pos(20.5, 40.0, 4.5))
        player.setHeldItemSlot(0)
        player.inventory.setItemStack(0, ItemStack.of(Material.BUNDLE))
        val hotbar = ItemStack.of(Material.APPLE, 2)
        val helmet = ItemStack.of(Material.DIAMOND_HELMET)
        val offhand = ItemStack.of(Material.SHIELD)
        val inventory = ItemStack.of(Material.EMERALD, 3).withCustomName(Component.text("Kit item"))
        player.inventory.setItemStack(1, hotbar)
        player.setEquipment(EquipmentSlot.HELMET, helmet)
        player.setEquipment(EquipmentSlot.OFF_HAND, offhand)
        player.inventory.setItemStack(9, inventory)

        val fillEvent = PlayerUseItemEvent(player, PlayerHand.MAIN, player.itemInMainHand, 0L)
        BundleListener.onUseItem(fillEvent)

        assertTrue(fillEvent.isCancelled)
        val filled = player.itemInMainHand
        val preview = filled.get(DataComponents.BUNDLE_CONTENTS)
        assertEquals(listOf(hotbar, helmet, offhand, inventory), preview)
        assertTrue(player.inventory.getItemStack(1).isAir)
        assertTrue(player.inventory.getItemStack(9).isAir)
        assertTrue(player.inventory.getItemStack(41).isAir)
        assertTrue(player.inventory.getItemStack(45).isAir)

        val restoreEvent = PlayerUseItemEvent(player, PlayerHand.MAIN, filled, 0L)
        BundleListener.onUseItem(restoreEvent)

        assertTrue(restoreEvent.isCancelled)
        assertTrue(player.itemInMainHand.isAir)
        assertEquals(hotbar, player.inventory.getItemStack(1))
        assertEquals(inventory, player.inventory.getItemStack(9))
        assertEquals(helmet, player.inventory.getItemStack(41))
        assertEquals(offhand, player.inventory.getItemStack(45))
        VanillaTest.remove(player)
    }

    @Test
    fun `bundle limit is inclusive`() {
        val player = VanillaTest.createPlayer(Pos(22.5, 40.0, 4.5))
        player.inventory.setItemStack(0, ItemStack.of(Material.BUNDLE))
        for (slot in 1..8) player.inventory.setItemStack(slot, ItemStack.of(Material.DIRT))
        player.setEquipment(EquipmentSlot.HELMET, ItemStack.of(Material.LEATHER_HELMET))
        player.setEquipment(EquipmentSlot.CHESTPLATE, ItemStack.of(Material.LEATHER_CHESTPLATE))
        player.setEquipment(EquipmentSlot.LEGGINGS, ItemStack.of(Material.LEATHER_LEGGINGS))
        player.setEquipment(EquipmentSlot.BOOTS, ItemStack.of(Material.LEATHER_BOOTS))
        player.setEquipment(EquipmentSlot.OFF_HAND, ItemStack.of(Material.SHIELD))
        for (slot in 9..11) player.inventory.setItemStack(slot, ItemStack.of(Material.STONE))

        BundleListener.onUseItem(PlayerUseItemEvent(player, PlayerHand.MAIN, player.itemInMainHand, 0L))

        assertEquals(Material.BUNDLE, player.itemInMainHand.material())
        assertTrue(player.inventory.getItemStack(1).isAir)
        assertTrue(player.inventory.getItemStack(11).isAir)
        VanillaTest.remove(player)
    }

    @Test
    fun `bundle does not capture more than configured limit`() {
        val player = VanillaTest.createPlayer(Pos(24.5, 40.0, 4.5))
        player.inventory.setItemStack(0, ItemStack.of(Material.BUNDLE))
        for (slot in 1..8) player.inventory.setItemStack(slot, ItemStack.of(Material.DIRT))
        EquipmentSlot.armors().forEach { player.setEquipment(it, ItemStack.of(Material.LEATHER_HELMET)) }
        player.setEquipment(EquipmentSlot.OFF_HAND, ItemStack.of(Material.SHIELD))
        for (slot in 9..12) player.inventory.setItemStack(slot, ItemStack.of(Material.STONE))
        val originalBundle = player.itemInMainHand

        BundleListener.onUseItem(PlayerUseItemEvent(player, PlayerHand.MAIN, originalBundle, 0L))

        assertEquals(originalBundle, player.itemInMainHand)
        assertTrue(!player.inventory.getItemStack(1).isAir)
        assertTrue(!player.inventory.getItemStack(12).isAir)
        VanillaTest.remove(player)
    }

    @Test
    fun `bundle restore keeps the bundle when a target slot is occupied`() {
        val player = VanillaTest.createPlayer(Pos(26.5, 40.0, 4.5))
        player.inventory.setItemStack(0, ItemStack.of(Material.BUNDLE))
        val stored = ItemStack.of(Material.DIAMOND)
        player.inventory.setItemStack(1, stored)
        BundleListener.onUseItem(PlayerUseItemEvent(player, PlayerHand.MAIN, player.itemInMainHand, 0L))
        val filled = player.itemInMainHand

        val conflicting = ItemStack.of(Material.DIRT)
        player.inventory.setItemStack(1, conflicting)
        BundleListener.onUseItem(PlayerUseItemEvent(player, PlayerHand.MAIN, filled, 0L))

        assertEquals(filled, player.itemInMainHand)
        assertEquals(conflicting, player.inventory.getItemStack(1))
        VanillaTest.remove(player)
    }

    @Test
    fun `filled bundle kit data survives player data serialization`() {
        val source = VanillaTest.createPlayer(Pos(28.5, 40.0, 4.5))
        source.inventory.setItemStack(0, ItemStack.of(Material.BUNDLE))
        val stored = ItemStack.of(Material.EMERALD, 4).withCustomName(Component.text("Persistent kit item"))
        source.inventory.setItemStack(1, stored)
        BundleListener.onUseItem(PlayerUseItemEvent(source, PlayerHand.MAIN, source.itemInMainHand, 0L))
        val data =
            net.aechronis.vanilla.serdes.PlayerDataSerializer
                .serialize(source)
        VanillaTest.remove(source)

        val restored = VanillaTest.createPlayer(Pos(30.5, 40.0, 4.5))
        net.aechronis.vanilla.serdes.PlayerDataDeserializer
            .deserialize(restored, data)
        val restoredBundle = restored.itemInMainHand
        assertEquals(Material.BUNDLE, restoredBundle.material())
        assertTrue(restored.inventory.getItemStack(1).isAir)

        BundleListener.onUseItem(PlayerUseItemEvent(restored, PlayerHand.MAIN, restoredBundle, 0L))

        assertEquals(stored, restored.inventory.getItemStack(1))
        assertTrue(restored.itemInMainHand.isAir)
        VanillaTest.remove(restored)
    }

    @Test
    fun `bundle object creates a restorable bundle above the player limit`() {
        val player = VanillaTest.createPlayer(Pos(32.5, 40.0, 4.5))
        val items = (1..17).associateWith { ItemStack.of(Material.DIRT, it) }

        val bundle = Bundle(items).makeBundle()
        player.inventory.setItemStack(0, bundle)

        assertEquals(items.values.toList(), bundle.get(DataComponents.BUNDLE_CONTENTS))

        BundleListener.onUseItem(PlayerUseItemEvent(player, PlayerHand.MAIN, bundle, 0L))

        items.forEach { (slot, item) -> assertEquals(item, player.inventory.getItemStack(slot)) }
        assertTrue(player.itemInMainHand.isAir)
        VanillaTest.remove(player)
    }

    @Test
    fun `bundle object preserves equal item stacks in separate slots`() {
        val player = VanillaTest.createPlayer(Pos(34.5, 40.0, 4.5))
        val item = ItemStack.of(Material.DIAMOND)
        val items = mapOf(1 to item, 2 to item)
        val bundle = Bundle(items).makeBundle()
        player.inventory.setItemStack(0, bundle)

        BundleListener.onUseItem(PlayerUseItemEvent(player, PlayerHand.MAIN, bundle, 0L))

        assertEquals(item, player.inventory.getItemStack(1))
        assertEquals(item, player.inventory.getItemStack(2))
        VanillaTest.remove(player)
    }
}
