package net.aechronis.combat.objects

/** Optional gun stations. Each gunner aims and reloads their own mount using this ammunition. */
class BoatArmament(
    val ammo: Ammo,
    weapons: List<BoatWeapon>,
    val maxAmmo: Int = 1,
) {
    val weapons: List<BoatWeapon> = weapons.toList()

    init {
        require(maxAmmo == 1) { "A boat shell reload supplies exactly one projectile" }
        require(this.weapons.isNotEmpty()) { "Boat armament must contain a gun station" }
        require(
            this.weapons
                .map { it.model }
                .distinct()
                .size == this.weapons.size,
        ) { "Boat weapon models must be unique" }
    }
}
