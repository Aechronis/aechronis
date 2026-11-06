package net.aechronis.server.constants

import net.aechronis.bundles.objects.Bundle
import net.aechronis.duels.Kit
import net.aechronis.duels.Kits
import net.aechronis.killshop.objects.ShopItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.item.ItemStack

/**
 * Chest layout, one row of nine slots each:
 * 0 kits, 1 ammo, 2 guns, 3 melee and explosives, 4 artillery (each piece followed by its free ammo).
 */
object ShopCatalog {
    private const val ROW = 9
    private const val KIT_ROW = 0
    private const val AMMO_ROW = 1
    private const val GUN_ROW = 2
    private const val MELEE_ROW = 3
    private const val ARTILLERY_ROW = 4
    private const val FREE_COOLDOWN_TICKS = 300L
    private const val ARTILLERY_AMMO = 8

    /** Call after the duel kits and items are registered. */
    fun items(): List<ShopItem> =
        row(KIT_ROW, Kits.all().map { kit -> free(kitBundle(kit)) }) +
            row(AMMO_ROW, listOf(free(Ammo.shotgunShell.toItemStack().withAmount(16)))) +
            row(
                GUN_ROW,
                listOf(
                    paid(Guns.spencerModel1860.toItemStack(), 4),
                    paid(Guns.winchesterModel1873.toItemStack(), 5),
                    paid(Guns.adamsMkIII.toItemStack(), 1),
                    paid(Guns.colt1851.toItemStack(), 1),
                    paid(Guns.coltSingleActionArmy.toItemStack(), 2),
                    paid(Guns.coachGun.toItemStack(), 3),
                ),
            ) +
            row(
                MELEE_ROW,
                listOf(
                    paid(Melees.cavalrySabre.toItemStack(), 3),
                    paid(Melees.navalCutlass.toItemStack(), 3),
                    paid(Grenades.castIronBall.toItemStack(), 3),
                ),
            ) +
            row(
                ARTILLERY_ROW,
                listOf(
                    paid(FieldPieces.kruppC64.toItemStack(), 15),
                    free(Ammo.artilleryShell.toItemStack().withAmount(ARTILLERY_AMMO)),
                    paid(FieldPieces.gatlingGun.toItemStack(), 5),
                    free(Ammo.gatlingFeedCase.toItemStack().withAmount(ARTILLERY_AMMO)),
                    paid(FieldPieces.maximGun.toItemStack(), 10),
                    free(Ammo.maximAmmunitionBelt.toItemStack().withAmount(ARTILLERY_AMMO)),
                ),
            )

    private fun row(
        row: Int,
        items: List<ShopItem>,
    ): List<ShopItem> {
        require(items.size <= ROW) { "Shop row $row holds at most $ROW items" }
        return items.mapIndexed { column, item -> item.copy(slot = row * ROW + column) }
    }

    private fun paid(
        item: ItemStack,
        cost: Int,
    ) = ShopItem(item, cooldownTicks = 0L, cost = cost)

    /** Free items have a short cooldown so they can't be farmed. */
    private fun free(item: ItemStack) = ShopItem(item, cooldownTicks = FREE_COOLDOWN_TICKS, cost = 0)

    private fun kitBundle(kit: Kit): ItemStack {
        val contents = kit.contents()
        val armor =
            mapOf(
                EquipmentSlot.HELMET to contents.helmet,
                EquipmentSlot.CHESTPLATE to contents.chestplate,
                EquipmentSlot.LEGGINGS to contents.leggings,
                EquipmentSlot.BOOTS to contents.boots,
            ).filterValues { !it.isAir }.mapKeys { it.key.armorSlot() }
        return Bundle(contents.items + armor)
            .makeBundle()
            .withCustomName(Component.text("${kit.displayName} Kit", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false))
    }
}
