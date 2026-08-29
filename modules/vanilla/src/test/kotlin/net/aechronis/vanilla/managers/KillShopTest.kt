package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.objects.ShopItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Pos
import net.minestom.server.inventory.Inventory
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class KillShopTest : ManagerTest() {
    @Test
    fun `item display shows affordability and cooldown state`() {
        val shopItem = ShopItem(ItemStack.of(Material.DIAMOND), cooldownTicks = 20, cost = 5)

        val ready = KillShop.buildItemDisplay(shopItem, points = 5, cooldownRemainingMs = 0)
        val readyLore = ready.get(DataComponents.LORE)!!
        assertEquals(Component.text("Cost: 5 points", NamedTextColor.YELLOW), readyLore[0])
        assertEquals(Component.text("Ready to buy", NamedTextColor.GREEN), readyLore[1])

        val unavailable = KillShop.buildItemDisplay(shopItem, points = 4, cooldownRemainingMs = 1_500)
        val unavailableLore = unavailable.get(DataComponents.LORE)!!
        assertEquals(Component.text("Cost: 5 points", NamedTextColor.RED), unavailableLore[0])
        assertEquals(Component.text("Cooldown: 1.5s", NamedTextColor.RED), unavailableLore[1])
    }

    @Test
    fun `opening a shop tracks its inventory and restock clears cooldowns`() {
        val player = VanillaTest.createPlayer(Pos(84.5, 40.0, 4.5))
        val cooldowns = KillShop.playerCooldowns.getOrPut(player.uuid) { ConcurrentHashMap() }
        cooldowns[0] = 1L

        KillShop.openShop(player)

        val inventory = player.openInventory as Inventory
        assertSame(player.openInventory, inventory)
        assertEquals(player.uuid, KillShop.openInventories[inventory])

        KillShop.restock()

        assertFalse(KillShop.playerCooldowns.containsKey(player.uuid))
        KillShop.openInventories.remove(inventory)
        player.closeInventory()
        VanillaTest.remove(player)
    }

    @Test
    fun `shop cooldown deadlines survive a module state handoff`() {
        val uuid = java.util.UUID.randomUUID()
        KillShop.playerCooldowns.getOrPut(uuid) { ConcurrentHashMap() }[2] = 5_000L

        try {
            val payload = KillShop.captureTransientState(now = 1_000L)
            KillShop.playerCooldowns.clear()
            KillShop.restoreTransientState(payload, now = 2_000L)

            assertEquals(5_000L, KillShop.playerCooldowns[uuid]?.get(2))
        } finally {
            KillShop.playerCooldowns.clear()
        }
    }

    @Test
    fun `corrupt shop cooldown handoff fails closed`() {
        assertFailsWith<IllegalArgumentException> {
            KillShop.restoreTransientState(byteArrayOf(0, 0, 0, 99))
        }
    }
}
