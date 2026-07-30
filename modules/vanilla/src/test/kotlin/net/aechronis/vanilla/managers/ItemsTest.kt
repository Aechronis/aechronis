package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.listeners.ItemListener
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.ItemEntity
import net.minestom.server.event.item.PickupItemEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemsTest : ManagerTest() {
    @Test
    fun `player pickup adds the complete item stack`() {
        val player = VanillaTest.createPlayer(Pos(8.5, 40.0, 4.5))
        val stack = ItemStack.of(Material.DIAMOND, 4).withCustomName(Component.text("Pickup test"))
        val event = PickupItemEvent(player, ItemEntity(stack))

        ItemListener.onPickup(event)

        assertFalse(event.isCancelled)
        assertEquals(stack, player.inventory.getItemStack(0))
        VanillaTest.remove(player)
    }

    @Test
    fun `pickup is cancelled when the complete stack does not fit`() {
        val player = VanillaTest.createPlayer(Pos(10.5, 40.0, 4.5))
        repeat(36) { player.inventory.setItemStack(it, ItemStack.of(Material.DIRT, 64)) }
        val existing = ItemStack.of(Material.DIAMOND, 63)
        player.inventory.setItemStack(0, existing)
        val event = PickupItemEvent(player, ItemEntity(ItemStack.of(Material.DIAMOND, 2)))

        ItemListener.onPickup(event)

        assertTrue(event.isCancelled)
        assertEquals(existing, player.inventory.getItemStack(0))
        VanillaTest.remove(player)
    }

    @Test
    fun `spectators and non-player entities cannot pick up items`() {
        val spectator = VanillaTest.createPlayer(Pos(12.5, 40.0, 4.5))
        spectator.setGameMode(GameMode.SPECTATOR)
        val spectatorEvent = PickupItemEvent(spectator, ItemEntity(ItemStack.of(Material.DIAMOND)))
        val creatureEvent = PickupItemEvent(EntityCreature(EntityType.ZOMBIE), ItemEntity(ItemStack.of(Material.EMERALD)))

        ItemListener.onPickup(spectatorEvent)
        ItemListener.onPickup(creatureEvent)

        assertTrue(spectatorEvent.isCancelled)
        assertTrue(spectator.inventory.getItemStack(0).isAir)
        assertTrue(creatureEvent.isCancelled)
        VanillaTest.remove(spectator)
    }
}
