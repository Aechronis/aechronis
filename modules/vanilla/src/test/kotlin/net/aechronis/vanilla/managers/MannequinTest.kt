package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.listeners.MannequinListener
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.PlayerSkin
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.item.ItemStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MannequinTest : ManagerTest() {
    @Test
    fun `death transfers equipped items to the corpse`() {
        val player = VanillaTest.createPlayer(Pos(8.5, 40.0, 8.5))
        player.skin = PlayerSkin("textures", "signature")
        val armor =
            mapOf(
                EquipmentSlot.HELMET to ItemStack.of(net.minestom.server.item.Material.DIAMOND_HELMET),
                EquipmentSlot.CHESTPLATE to ItemStack.of(net.minestom.server.item.Material.DIAMOND_CHESTPLATE),
                EquipmentSlot.LEGGINGS to ItemStack.of(net.minestom.server.item.Material.DIAMOND_LEGGINGS),
                EquipmentSlot.BOOTS to ItemStack.of(net.minestom.server.item.Material.DIAMOND_BOOTS),
            )
        armor.forEach(player::setEquipment)
        val offhand = ItemStack.of(net.minestom.server.item.Material.SHIELD)
        player.setEquipment(EquipmentSlot.OFF_HAND, offhand)
        val existingCorpses = Mannequin.inventories.keys.toSet()

        MannequinListener.onDeath(PlayerDeathEvent(player, Component.empty(), null))

        val corpse = (Mannequin.inventories.keys - existingCorpses).single()
        val loot = Mannequin.inventories.getValue(corpse)
        for ((slot, item) in armor) {
            assertEquals(item, loot.getItemStack(slot.armorSlot()))
            assertEquals(item, corpse.getEquipment(slot))
            assertEquals(ItemStack.AIR, player.getEquipment(slot))
        }
        assertEquals(offhand, loot.getItemStack(45))
        assertEquals(ItemStack.AIR, player.getEquipment(EquipmentSlot.OFF_HAND))

        Mannequin.unregister(corpse)
        corpse.remove()
        VanillaTest.remove(player)
    }

    @Test
    fun `death with an empty inventory does not create a corpse`() {
        val player = VanillaTest.createPlayer(Pos(10.5, 40.0, 8.5))
        val existingCorpses = Mannequin.inventories.keys.toSet()

        MannequinListener.onDeath(PlayerDeathEvent(player, Component.empty(), null))

        assertEquals(existingCorpses, Mannequin.inventories.keys.toSet())
        VanillaTest.remove(player)
    }

    @Test
    fun `taking the final corpse item despawns it`() {
        val player = VanillaTest.createPlayer(Pos(12.5, 40.0, 8.5))
        player.skin = PlayerSkin("textures", "signature")
        player.inventory.setItemStack(0, ItemStack.of(net.minestom.server.item.Material.DIAMOND))
        val existingCorpses = Mannequin.inventories.keys.toSet()
        MannequinListener.onDeath(PlayerDeathEvent(player, Component.empty(), null))
        val corpse = (Mannequin.inventories.keys - existingCorpses).single()
        val loot = Mannequin.inventories.getValue(corpse)
        player.openInventory(loot)

        loot.setItemStack(0, ItemStack.AIR)

        assertFalse(Mannequin.inventories.containsKey(corpse))
        assertNull(player.openInventory)
        assertNull(corpse.instance)
        VanillaTest.remove(player)
    }

    @Test
    fun `corpse armor follows armor remaining in its inventory`() {
        val corpse = EntityCreature(EntityType.MANNEQUIN)
        val loot = Mannequin.newLootInventory("test")
        val equippedHelmet = ItemStack.of(net.minestom.server.item.Material.DIAMOND_HELMET)
        val spareHelmet = ItemStack.of(net.minestom.server.item.Material.GOLDEN_HELMET)
        loot.setItemStack(EquipmentSlot.HELMET.armorSlot(), equippedHelmet)
        loot.setItemStack(0, spareHelmet)
        loot.setItemStack(1, ItemStack.of(net.minestom.server.item.Material.DIAMOND))
        Mannequin.register(corpse, loot)

        assertEquals(equippedHelmet, corpse.getEquipment(EquipmentSlot.HELMET))
        loot.setItemStack(EquipmentSlot.HELMET.armorSlot(), ItemStack.AIR)
        assertEquals(spareHelmet, corpse.getEquipment(EquipmentSlot.HELMET))
        loot.setItemStack(0, ItemStack.AIR)
        assertEquals(ItemStack.AIR, corpse.getEquipment(EquipmentSlot.HELMET))
        loot.setItemStack(10, equippedHelmet)
        assertEquals(equippedHelmet, corpse.getEquipment(EquipmentSlot.HELMET))

        Mannequin.unregister(corpse)
        corpse.remove()
    }
}
