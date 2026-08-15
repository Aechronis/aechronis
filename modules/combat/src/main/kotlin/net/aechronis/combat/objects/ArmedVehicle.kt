package net.aechronis.combat.objects

/** A vehicle with a reloadable ammunition magazine. */
interface ArmedVehicle {
    val ammo: Ammo
    val maxAmmo: Int
}
