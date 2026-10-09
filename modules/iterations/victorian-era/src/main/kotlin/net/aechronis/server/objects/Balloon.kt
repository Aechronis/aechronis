package net.aechronis.server.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.listeners.MannequinDamageListener
import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.AnimatedPart
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.objects.VehicleSeat
import net.aechronis.combat.objects.VehicleSeatRole
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.Damage
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Slow lighter-than-air vehicle.
 *
 * Driver keys: forward = fly where you look, jump = climb, backward = descend, none = hover.
 * When fuel is enabled ([maxFuel] > 0) the burner drains while someone pilots, coal refills it,
 * an empty or abandoned balloon falls, and landing hard hurts the hull and its riders.
 * A destroyed balloon sinks to the ground instead of exploding.
 */
open class Balloon(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    model: String = "${Tags.NAMESPACE}:$name",
    scale: Double,
    hitbox: Hitbox,
    health: Health,
    placeTime: Long = 3000,
    /** Blocks per tick flying forward. */
    val horizontalSpeed: Double = 0.15,
    val climbSpeed: Double = 0.1,
    val descendSpeed: Double = 0.1,
    /** Fraction of the gap to the target velocity closed each tick. */
    val responsiveness: Double = 0.05,
    /** Highest Y the balloon's origin may reach. */
    val maxAltitude: Double = 150.0,
    /** Degrees per tick the hull turns toward the driver's view; 0 keeps the hull fixed and flies along the view. */
    val turnSpeed: Float = 0f,
    /** Fuel capacity in ticks of flight. 0 disables fuel entirely. */
    val maxFuel: Int = 0,
    /** Ticks of flight one piece of coal buys (45 seconds). */
    val coalFuel: Int = 900,
    /** Blocks per tick an abandoned balloon drifts down. */
    val driftSpeed: Double = 0.04,
    /** Blocks per tick an occupied balloon plummets once its fuel runs out. */
    val fallSpeed: Double = 0.35,
    /** Rifle-hit equivalents of damage the hull takes when it lands without fuel. */
    val crashHits: Int = 3,
    /** Fall damage dealt to every occupant on that landing. */
    val crashPlayerDamage: Float = 6f,
    /** Blocks per tick a destroyed balloon sinks. */
    val sinkSpeed: Double = 0.08,
    seats: List<VehicleSeat> = listOf(VehicleSeat("driver", "Pilot", VehicleSeatRole.DRIVER)),
    invisibleWhileRiding: Boolean = true,
    invulnerableWhileRiding: Boolean = true,
    animatedParts: List<AnimatedPart> = emptyList(),
    collisionHitbox: ShulkerHitbox = ShulkerHitbox.fromHitbox(hitbox),
) : Vehicle(
        name,
        itemName,
        itemLore,
        itemModel,
        model,
        scale,
        hitbox,
        health,
        placeTime,
        seats,
        invisibleWhileRiding,
        invulnerableWhileRiding,
        animatedParts,
        collisionHitbox,
    ) {
    private val hasFuel: Boolean get() = maxFuel > 0

    // ===== Ticking =====

    override fun onTick(player: Player) {
        val entity = driverEntity(player) ?: return
        when {
            entity in sinking -> sink(entity)
            isOutOfFuel(entity) -> plummet(entity)
            else -> {
                if (hasFuel) fuels[entity] = fuel(entity) - 1
                steer(player, entity)
            }
        }
        super.onTick(player)
    }

    override fun onUnoccupiedTick(entity: Entity) {
        when {
            entity in sinking -> sink(entity)
            !hasFuel -> Unit
            occupants(entity).isEmpty() -> drift(entity)
            isOutOfFuel(entity) -> plummet(entity)
        }
    }

    // ===== Flight =====

    /** Turns the hull toward the driver's view (if it turns) and eases velocity toward the pressed keys. */
    private fun steer(
        player: Player,
        entity: Entity,
    ) {
        if (turnSpeed > 0f) turnHull(player, entity)
        val input = KeyPressListener.playerInputEvent[player]
        val heading = Math.toRadians((if (turnSpeed > 0f) entity.position.yaw else player.position.yaw).toDouble())
        val forward = if (input?.isHoldingForwardKey == true) horizontalSpeed else 0.0
        val vertical =
            when {
                input?.isHoldingJumpKey == true -> climbSpeed
                input?.isHoldingBackwardKey == true -> -descendSpeed
                else -> 0.0
            }
        val target = Vec(-sin(heading) * forward, vertical, cos(heading) * forward)
        val velocity = velocities[entity] ?: Vec.ZERO
        move(entity, velocity.add(target.sub(velocity).mul(responsiveness)))
    }

    private fun turnHull(
        player: Player,
        entity: Entity,
    ) {
        val yaw = entity.position.yaw
        val delta = ((player.position.yaw - yaw + 540f) % 360f) - 180f
        entity.setView(yaw + delta.coerceIn(-turnSpeed, turnSpeed), 0f)
    }

    /** Moves one axis at a time so the balloon slides along walls; blocked axes lose their velocity. */
    private fun move(
        entity: Entity,
        velocity: Vec,
    ) {
        val instance = entity.instance ?: return
        var position = entity.position
        var remaining = velocity
        val axes =
            listOf<Pair<Vec, (Vec) -> Vec>>(
                Vec(velocity.x, 0.0, 0.0) to { v -> v.withX(0.0) },
                Vec(0.0, velocity.y, 0.0) to { v -> v.withY(0.0) },
                Vec(0.0, 0.0, velocity.z) to { v -> v.withZ(0.0) },
            )
        for ((step, stop) in axes) {
            val candidate = position.add(step).let { if (it.y > maxAltitude) it.withY(maxAltitude) else it }
            if (hitbox.checkGroundCollision(instance, candidate, candidate.yaw, candidate.pitch, 0f)) {
                remaining = stop(remaining)
            } else {
                position = candidate
            }
        }
        velocities[entity] = remaining
        if (position != entity.position) entity.teleport(position)
    }

    // ===== Fuel =====

    private fun fuel(entity: Entity): Int = fuels[entity] ?: 0

    private fun isOutOfFuel(entity: Entity): Boolean = hasFuel && fuel(entity) <= 0

    /** Right-clicking with coal (from the ground or from a seat) tops up the burner. */
    override fun onInteract(
        player: Player,
        entity: Entity,
    ): Boolean {
        val held = player.itemInMainHand
        if (!hasFuel || held.material() != Material.COAL) return false
        if (fuel(entity) < maxFuel) {
            fuels[entity] = (fuel(entity) + coalFuel).coerceAtMost(maxFuel)
            falling.remove(entity)
            player.itemInMainHand = if (held.amount() <= 1) ItemStack.AIR else held.withAmount(held.amount() - 1)
        }
        return true
    }

    override fun telemetryText(entity: Entity): String? {
        if (!hasFuel) return null
        val filled = (fuel(entity) * BAR_SEGMENTS + maxFuel - 1) / maxFuel
        return "Fuel: [" + "|".repeat(filled) + " ".repeat(BAR_SEGMENTS - filled) + "]"
    }

    // ===== Falling =====

    /** Abandoned: lower gently until the ground, with no damage. */
    private fun drift(entity: Entity) {
        falling.remove(entity)
        move(entity, Vec(0.0, -driftSpeed, 0.0))
    }

    /** Occupied and out of fuel: fall fast, then hurt the hull and everyone aboard once on the ground. */
    private fun plummet(entity: Entity) {
        val before = entity.position.y
        move(entity, Vec(0.0, -fallSpeed, 0.0))
        if (entity.position.y < before) {
            falling.add(entity)
            return
        }
        // only a balloon that actually fell crashes; a fresh empty one sitting on the ground does not
        if (falling.remove(entity)) crash(entity)
    }

    private fun crash(entity: Entity) {
        val fall = Damage(DamageType.FALL, null, null, null, crashPlayerDamage)
        occupants(entity).forEach { MannequinDamageListener.forwardDamage(it, fall) }
        repeat(crashHits) { takeDamage(entity, AmmoTypes.NORMAL, 0f, null, null) }
    }

    // ===== Destruction =====

    /** Health depletion starts the slow descent; a sinking balloon cannot be damaged further. */
    override fun takeDamage(
        entity: Entity,
        ammoType: AmmoTypes?,
        amount: Float,
        attacker: Player?,
        weapon: Component?,
    ): Boolean = entity !in sinking && super.takeDamage(entity, ammoType, amount, attacker, weapon)

    /** First call starts sinking; the call made when it touches ground removes the balloon. */
    override fun destroy(
        entity: Entity,
        attacker: Player?,
        weapon: Component?,
    ) {
        if (sinking.add(entity)) {
            velocities.remove(entity)
            return
        }
        cleanupRuntime(entity)
        super.destroy(entity, attacker, weapon)
    }

    private fun sink(entity: Entity) {
        val instance = entity.instance ?: return
        val next = entity.position.add(0.0, -sinkSpeed, 0.0)
        if (hitbox.checkGroundCollision(instance, next, next.yaw, next.pitch, 0f)) {
            destroy(entity)
        } else {
            entity.teleport(next)
        }
    }

    override fun cleanupRuntime(entity: Entity) {
        velocities.remove(entity)
        sinking.remove(entity)
        fuels.remove(entity)
        falling.remove(entity)
    }

    private companion object {
        const val BAR_SEGMENTS = 10

        // per-hull state, cleared in cleanupRuntime; weak so a hull removed another way cannot leak
        val velocities = WeakHashMap<Entity, Vec>()
        val fuels = WeakHashMap<Entity, Int>()
        val sinking: MutableSet<Entity> = Collections.newSetFromMap(WeakHashMap())
        val falling: MutableSet<Entity> = Collections.newSetFromMap(WeakHashMap())
    }
}
