package net.aechronis.server.constants

import net.aechronis.vanilla.objects.ShopItem

object ShopCatalog {
    private const val KIT_COOLDOWN_TICKS = 300L

    val items: List<ShopItem> by lazy {
        Kits.all.map { kit -> ShopItem(kit, cooldownTicks = KIT_COOLDOWN_TICKS, cost = 0) } +
            listOf(
                ShopItem(Planes.b2.toItemStack(), cooldownTicks = 0L, cost = 25),
                ShopItem(Planes.f16.toItemStack(), cooldownTicks = 0L, cost = 10),
                ShopItem(Planes.j20.toItemStack(), cooldownTicks = 0L, cost = 20),
                ShopItem(Planes.su34.toItemStack(), cooldownTicks = 0L, cost = 25),
                ShopItem(Planes.su57.toItemStack(), cooldownTicks = 0L, cost = 25),
                // ShopItem(Cars.humvee.toItemStack(), cooldownTicks = 0L, cost = 2),
                ShopItem(Tanks.m1a1Abrams.toItemStack(), cooldownTicks = 0L, cost = 20),
                ShopItem(Tanks.t90.toItemStack(), cooldownTicks = 0L, cost = 20),
                ShopItem(Ammo.tankShell.toItemStack(), cooldownTicks = 0L, cost = 5),
                ShopItem(Ammo.ammo762x39mmExplosive.toItemStack(), cooldownTicks = 0L, cost = 5),
                ShopItem(Guns.at4.toItemStack(), cooldownTicks = 0L, cost = 15),
                ShopItem(Ammo.rocket.toItemStack(), cooldownTicks = 0L, cost = 5),
                ShopItem(Grenades.rgo.toItemStack(), cooldownTicks = 0L, cost = 5),
                ShopItem(Drones.scoutDrone.toItemStack(), cooldownTicks = 0L, cost = 5),
                ShopItem(Drones.kamikazeDrone.toItemStack(), cooldownTicks = 0L, cost = 7),
            )
    }
}
