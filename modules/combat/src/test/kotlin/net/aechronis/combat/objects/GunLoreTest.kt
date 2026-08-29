package net.aechronis.combat.objects

import net.aechronis.combat.CombatTestServer
import net.aechronis.combat.listeners.WeaponLoreListener
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.component.DataComponents
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GunLoreTest {
    @Test
    fun `gun item lore shows its gameplay stats`() {
        val description =
            Component
                .text("Custom description", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)
        val ammoName =
            Component
                .text("Test Ammo", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false)
        val ammo =
            Ammo(
                name = "test-ammo",
                ammoType = AmmoTypes.NORMAL,
                itemName = ammoName,
            )
        val gun =
            Gun(
                name = "test-gun",
                itemName = Component.text("Test Gun", NamedTextColor.GOLD),
                itemLore = listOf(description),
                ammo = ammo,
                maxAmmo = 1,
                damage = 12.5F,
                automatic = false,
                sniper = true,
                cooldown = 125,
                reloadTime = 2750,
                recoilMin = 0.5F,
                recoilMax = 2F,
                spreadMin = 0.1F,
                spreadMax = 3F,
                maxRange = 96.5,
            )

        assertEquals(
            listOf(
                description,
                stat("Damage: 12.5"),
                Component
                    .text("Ammo: ", NamedTextColor.GRAY)
                    .append(ammoName)
                    .decoration(TextDecoration.ITALIC, false),
                stat("Magazine: 1 round"),
                stat("Fire mode: Semi-automatic"),
                stat("Fire rate: 480 RPM"),
                stat("Reload: 2.75s"),
                stat("Recoil: 0.5-2°"),
                stat("Spread: 0.1-3°"),
                stat("Range: 96.5 blocks"),
                stat("Scope: Yes"),
            ),
            gun.itemLore,
        )
    }

    @Test
    fun `existing weapon lore updates without changing its state`() {
        CombatTestServer.initialize()

        val gun =
            Gun(
                name = "updated-test-gun",
                itemName = Component.text("Updated Test Gun", NamedTextColor.GOLD),
                ammo =
                    Ammo(
                        name = "updated-test-ammo",
                        ammoType = AmmoTypes.NORMAL,
                        itemName = Component.text("Updated Test Ammo", NamedTextColor.GOLD),
                    ),
                maxAmmo = 30,
                damage = 18F,
                automatic = true,
                sniper = false,
                cooldown = 100,
                reloadTime = 3000,
                recoilMin = 1F,
                recoilMax = 4F,
                spreadMin = 0.25F,
                spreadMax = 2F,
            )
        Item.registerItems(gun)

        val oldLore = listOf(stat("Damage: 12"))
        val oldStack =
            gun
                .toItemStack()
                .withLore(oldLore)
                .with(DataComponents.DAMAGE, 73)
                .withItemModel(gun.itemModelAiming)
                .withAmount(2)

        val inventory = Inventory(InventoryType.CHEST_1_ROW, Component.text("Test Inventory"))
        inventory.setItemStack(0, oldStack)
        WeaponLoreListener.onInventoryItemChange(
            InventoryItemChangeEvent(inventory, 0, ItemStack.AIR, oldStack),
        )
        val refreshed = inventory.getItemStack(0)

        assertEquals(gun.itemLore, refreshed.get(DataComponents.LORE))
        assertEquals(73, refreshed.get(DataComponents.DAMAGE))
        assertEquals(gun.itemModelAiming, refreshed.get(DataComponents.ITEM_MODEL))
        assertEquals(2, refreshed.amount())
        assertSame(refreshed, WeaponLoreListener.refreshLore(refreshed))
    }

    private fun stat(text: String): Component =
        Component
            .text(text, NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false)
}
