package net.aechronis.server.constants

import net.aechronis.vanilla.objects.ShopItem

object ShopCatalog {
    private const val KIT_COOLDOWN_TICKS = 300L

    val items: List<ShopItem> by lazy {
        Kits.all.map { kit -> ShopItem(kit, cooldownTicks = KIT_COOLDOWN_TICKS, cost = 0) } +
            listOf(
                ShopItem(Planes.f16.toItemStack(), cooldownTicks = 0L, cost = 10),
                ShopItem(Cars.truck.toItemStack(), cooldownTicks = 0L, cost = 2),
                ShopItem(Tanks.m1a1Abrams.toItemStack(), cooldownTicks = 0L, cost = 20),
                ShopItem(Drones.scoutDrone.toItemStack(), cooldownTicks = 0L, cost = 5),
                ShopItem(Drones.kamikazeDrone.toItemStack(), cooldownTicks = 0L, cost = 7),
            )
    }
}
