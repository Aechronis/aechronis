package net.aechronis.combat.objects

import net.minestom.server.coordinate.Point
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

/** A thrown item landing on a block, or on [target] when it hit an entity. */
class ThrowableImpact(
    val throwable: Throwable,
    val thrower: Player,
    val instance: Instance,
    val target: LivingEntity?,
    val point: Point,
)

object ThrowableImpacts {
    fun explosion(
        radius: Int,
        damage: Float,
        fire: Double = 0.0,
        ammoType: AmmoTypes? = AmmoTypes.EXPLOSIVE,
    ): (ThrowableImpact) -> Unit {
        require(radius >= 0) { "Explosion radius must be non-negative" }
        require(damage >= 0f) { "Explosion damage must be non-negative" }
        require(fire.isFinite() && fire in 0.0..1.0) { "Explosion fire must be between 0 and 1" }
        return { impact ->
            Explosion(
                instance = impact.instance,
                pos = impact.point.asPos(),
                radius = radius,
                fire = fire,
                damage = damage,
                source = impact.thrower,
                weapon = impact.throwable.itemName,
                ammoType = ammoType,
            )
        }
    }
}
