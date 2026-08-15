package net.aechronis.combat.objects

class Health(
    health: Float,
    damageByAmmoType: Map<AmmoTypes, Float>,
) {
    val maxHealth: Float = health
    val damageByAmmoType: Map<AmmoTypes, Float> = damageByAmmoType.toMap()

    var health: Float = health
        private set

    fun takeHp(ammoType: AmmoTypes): Boolean {
        val damage = damageByAmmoType[ammoType] ?: return false
        health = (health - damage).coerceAtLeast(0f)
        return health <= 0f
    }

    /** Restores persisted health while preserving this definition's configured maximum. */
    internal fun restore(amount: Float) {
        health = amount.coerceIn(0f, maxHealth)
    }

    internal fun fresh(): Health = Health(maxHealth, damageByAmmoType)
}
