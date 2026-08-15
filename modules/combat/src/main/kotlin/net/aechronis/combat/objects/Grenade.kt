package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.constants.Tags
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.timer.TaskSchedule

class Grenade(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    val fuseTimeMillis: Long = 5_000L,
    val throwSpeed: Double = 1.0,
    val gravity: Double = 0.05,
    val explosionRadius: Int = 4,
    val explosionFire: Double = 0.0,
    val explosionDamage: Float = 20f,
    val ammoType: AmmoTypes? = AmmoTypes.EXPLOSIVE,
    val projectileModel: String = itemModel,
) : Item(name, itemName, itemLore, itemModel, Material.FIREWORK_STAR) {
    init {
        require(fuseTimeMillis > 0) { "Grenade fuseTimeMillis must be positive" }
        require(throwSpeed.isFinite() && throwSpeed > 0.0) { "Grenade throwSpeed must be positive and finite" }
        require(gravity.isFinite() && gravity >= 0.0) { "Grenade gravity must be non-negative and finite" }
        require(explosionRadius >= 0) { "Grenade explosionRadius must be non-negative" }
        require(explosionFire.isFinite() && explosionFire in 0.0..1.0) {
            "Grenade explosionFire must be between 0 and 1"
        }
        require(explosionDamage >= 0f) { "Grenade explosionDamage must be non-negative" }
    }

    fun use(player: Player): Boolean {
        val armed = Combat.armedGrenades[player]
        return if (armed === this) {
            throwGrenade(player)
        } else if (armed == null && Item.getFromItemStack(player.itemInMainHand) === this) {
            arm(player)
            true
        } else {
            false
        }
    }

    private fun arm(player: Player) {
        val deadline = System.currentTimeMillis() + fuseTimeMillis
        Combat.armedGrenades[player] = this
        Combat.grenadeFuseDeadlines[player] = deadline
        Combat.grenadeFuseTasks[player] =
            MinecraftServer
                .getSchedulerManager()
                .buildTask {
                    if (Combat.armedGrenades[player] === this) detonateInHand(player)
                }.delay(TaskSchedule.millis(fuseTimeMillis))
                .schedule()
    }

    private fun throwGrenade(player: Player): Boolean {
        val deadline = System.currentTimeMillis() + remainingFuse(player)
        clearArmed(player)

        // Switching the item while primed cannot create or duplicate a grenade.
        if (Item.getFromItemStack(player.itemInMainHand) !== this) {
            detonate(player.position, player)
            return true
        }

        consumeOne(player)
        val origin = player.position.add(0.0, player.eyeHeight, 0.0)
        val direction = origin.direction()
        if (deadline <= System.currentTimeMillis()) {
            detonate(origin, player)
        } else {
            Projectile(
                instance = player.instance,
                pos = origin.add(direction.mul(0.35)),
                model = projectileModel,
                direction = direction,
                speed = throwSpeed,
                gravity = gravity,
                explosionRadius = explosionRadius,
                explosionFire = explosionFire,
                explosionDamage = explosionDamage,
                source = player,
                ammoType = ammoType,
                weapon = itemName,
                fuseDeadlineMillis = deadline,
            )
        }
        return true
    }

    private fun remainingFuse(player: Player): Long {
        // The arm time is kept in the task deadline, so use the task's deadline map.
        return (Combat.grenadeFuseDeadlines[player] ?: System.currentTimeMillis()) - System.currentTimeMillis()
    }

    private fun consumeOne(player: Player) {
        val item = player.itemInMainHand
        player.itemInMainHand =
            if (item.amount() <= 1) {
                ItemStack.AIR
            } else {
                item.withAmount(item.amount() - 1)
            }
    }

    private fun detonate(
        pos: Pos,
        source: Player,
    ) {
        Explosion(
            instance = source.instance,
            pos = pos,
            radius = explosionRadius,
            fire = explosionFire,
            damage = explosionDamage,
            source = source,
            weapon = itemName,
            ammoType = ammoType,
        )
    }

    companion object {
        fun detonateInHand(player: Player) {
            val grenade = Combat.armedGrenades[player] ?: return
            val pos = player.position.add(0.0, player.eyeHeight, 0.0)
            grenade.consumeOne(player)
            clearArmed(player)
            grenade.detonate(pos, player)
        }

        fun clearArmed(player: Player) {
            Combat.grenadeFuseTasks.remove(player)?.cancel()
            Combat.grenadeFuseDeadlines.remove(player)
            Combat.armedGrenades.remove(player)
        }
    }
}
