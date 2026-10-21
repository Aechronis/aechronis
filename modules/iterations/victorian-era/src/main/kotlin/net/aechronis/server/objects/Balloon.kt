package net.aechronis.server.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.AnimatedPart
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.objects.VehicleSeat
import net.aechronis.combat.objects.VehicleSeatRole
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.sound.SoundStop
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import java.time.Duration
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Slow lighter-than-air vehicle.
 *
 * Driver keys: forward = fly where you look, backward = reverse, jump = climb, sneak = descend, none = coast to a hover.
 * The swap-hands key dismounts.
 * An empty balloon drifts down on its own, and a destroyed one sinks to the ground instead of exploding.
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
    /** Blocks per tick squared that horizontal speed changes by; coasting to a stop is twice as fast. */
    val acceleration: Double = 0.004,
    val verticalAcceleration: Double = 0.005,
    /** Highest Y the balloon's origin may reach. */
    val maxAltitude: Double = 150.0,
    /** Degrees per tick the hull turns toward the driver's view; 0 keeps the hull fixed and flies along the view. */
    val turnSpeed: Float = 0f,
    /** Share of [turnSpeed] left at a standstill; below 1 the hull turns only as tightly as its speed allows, so turns stay wide. */
    val minTurnFraction: Double = 1.0,
    /** Share of top speed shed while turning at the full rate. */
    val turnSlowdown: Double = 0.0,
    /** Blocks per tick an abandoned balloon drifts down. */
    val driftSpeed: Double = 0.04,
    /** Blocks per tick a destroyed balloon sinks. */
    val sinkSpeed: Double = 0.08,
    /** Sound that follows the hull while it moves, and how many ticks one play lasts before it is replayed. */
    val engineSound: Sound? = null,
    val engineSoundTicks: Int = 0,
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
    override val exitsOnSneak = false
    override val exitsOnSwapHands = true
    override val decksInsideHitbox = true

    // the hull moves every tick, so the client may only smooth over a tick or two or the deck swims under the crew
    override val interpolationTicks = 2

    // ===== Ticking =====

    override fun onEnter(
        player: Player,
        entity: Entity,
    ) {
        super.onEnter(player, entity)
        if (driverEntity(player) === entity && shownControls.add(player)) showControls(player)
    }

    override fun onTick(player: Player) {
        val entity = driverEntity(player) ?: return
        when {
            entity in sinking -> sink(entity)
            else -> steer(player, entity)
        }
        updateEngine(entity)
        super.onTick(player)
    }

    override fun onUnoccupiedTick(entity: Entity) {
        when {
            entity in sinking -> sink(entity)
            // an abandoned balloon sinks; one with only gunners aboard holds its height
            else -> glide(entity, if (occupants(entity).isEmpty()) -driftSpeed else 0.0)
        }
        updateEngine(entity)
    }

    // ===== Flight =====

    /** Turns the hull toward the driver's view (if it turns) and eases velocity toward the pressed keys. */
    private fun steer(
        player: Player,
        entity: Entity,
    ) {
        val input = KeyPressListener.playerInputEvent[player]
        var velocity = velocities[entity] ?: Vec.ZERO
        var turnFraction = 0.0
        if (turnSpeed > 0f) {
            val applied = turnHull(player, entity, hypot(velocity.x, velocity.z))
            // momentum follows the hull round the bend instead of sliding sideways
            velocity = velocity.rotatedBy(applied)
            turnFraction = abs(applied) / turnSpeed.toDouble()
        }
        val heading = Math.toRadians((if (turnSpeed > 0f) entity.position.yaw else player.position.yaw).toDouble())
        val forward = input?.isHoldingForwardKey == true
        val backward = input?.isHoldingBackwardKey == true
        val thrust =
            when {
                forward && !backward -> 1.0
                backward && !forward -> -REVERSE_SPEED
                else -> 0.0
            }
        val speed = thrust * horizontalSpeed * (1.0 - turnSlowdown * turnFraction)
        val climb =
            when {
                input?.isHoldingJumpKey == true -> climbSpeed
                input?.isHoldingShiftKey == true -> -descendSpeed
                else -> 0.0
            }
        val notice = fly(entity, velocity, Vec(-sin(heading) * speed, climb, cos(heading) * speed))
        if (entity.aliveTicks % 4L == 0L) player.sendActionBar(hud(entity, notice))
    }

    /** Rotates the hull toward the driver's view, returning the degrees actually turned. */
    private fun turnHull(
        player: Player,
        entity: Entity,
        speed: Double,
    ): Float {
        val yaw = entity.position.yaw
        val delta = ((player.position.yaw - yaw + 540f) % 360f) - 180f
        val rate = turnSpeed * (speed / horizontalSpeed).coerceIn(minTurnFraction, 1.0).toFloat()
        val applied = delta.coerceIn(-rate, rate)
        entity.setView(yaw + applied, 0f)
        return applied
    }

    private fun hud(
        entity: Entity,
        notice: String?,
    ): Component {
        val velocity = velocities[entity] ?: Vec.ZERO
        val line =
            Component.text(
                "Alt %.0f/%.0f  |  %.1f b/s".format(Locale.ROOT, entity.position.y, maxAltitude, hypot(velocity.x, velocity.z) * 20.0),
                NamedTextColor.GRAY,
            )
        return if (notice == null) line else line.append(Component.text("  |  $notice", NamedTextColor.RED))
    }

    /** What touches the ground: the hitbox, plus any hanging model parts a subclass adds below it. */
    protected open val groundHitbox: Hitbox get() = hitbox

    /** Eases [velocity] toward [target], braking harder than accelerating, then moves. Returns what stopped it, if anything. */
    private fun fly(
        entity: Entity,
        velocity: Vec,
        target: Vec,
    ): String? {
        val current = Vec(velocity.x, 0.0, velocity.z)
        val goal = Vec(target.x, 0.0, target.z)
        val step = if (goal.length() < current.length()) acceleration * BRAKING else acceleration
        val horizontal = approach(current, goal, step)
        val vertical = velocity.y + (target.y - velocity.y).coerceIn(-verticalAcceleration, verticalAcceleration)
        return move(entity, Vec(horizontal.x, vertical, horizontal.z))
    }

    /** No driver: coast to a stop horizontally while holding height or sinking at [fall]. */
    private fun glide(
        entity: Entity,
        fall: Double,
    ) {
        val velocity = velocities[entity] ?: Vec.ZERO
        // nothing to do for a hull at rest, or resting on the ground and only trying to sink
        if (velocity == Vec.ZERO && (fall == 0.0 || !isAirborne(entity))) return
        fly(entity, velocity, Vec(0.0, fall, 0.0))
    }

    /** Moves one axis at a time so the balloon slides along walls; blocked axes lose their velocity. */
    private fun move(
        entity: Entity,
        velocity: Vec,
    ): String? {
        val instance = entity.instance ?: return null
        var position = entity.position
        var remaining = velocity
        var notice: String? = null

        fun slide(
            step: Vec,
            stop: (Vec) -> Vec,
            blocked: String,
        ) {
            if (step == Vec.ZERO) return
            var candidate = position.add(step)
            if (candidate.y > maxAltitude) {
                candidate = candidate.withY(maxAltitude)
                remaining = remaining.withY(0.0)
                notice = "Ceiling"
            }
            if (candidate == position) return
            if (groundHitbox.checkGroundCollision(instance, candidate, candidate.yaw, candidate.pitch, 0f)) {
                remaining = stop(remaining)
                if (step.length() > NOTICE_STEP) notice = notice ?: blocked
            } else {
                position = candidate
            }
        }
        slide(Vec(velocity.x, 0.0, 0.0), { it.withX(0.0) }, "Blocked")
        slide(Vec(0.0, velocity.y, 0.0), { it.withY(0.0) }, if (velocity.y < 0) "Ground" else "Blocked")
        slide(Vec(0.0, 0.0, velocity.z), { it.withZ(0.0) }, "Blocked")
        if (remaining == Vec.ZERO) velocities.remove(entity) else velocities[entity] = remaining
        if (position != entity.position) entity.teleport(position)
        return notice
    }

    // ===== Sound and hints =====

    /** Whether the hull is clear of the ground; checked every few ticks, since a resting hull stays resting. */
    private fun isAirborne(entity: Entity): Boolean {
        val instance = entity.instance ?: return false
        if (entity in sinking) return false
        val cached = airborne[entity]
        if (cached != null && entity.aliveTicks % AIRBORNE_CHECK_TICKS != 0L) return cached
        val below = entity.position.add(0.0, -0.25, 0.0)
        return (!groundHitbox.checkGroundCollision(instance, below, below.yaw, below.pitch, 0f)).also { airborne[entity] = it }
    }

    /** Loops the engine for as long as the hull is in the air. Each hull plays its own numbered copy so it can be stopped alone. */
    private fun updateEngine(entity: Entity) {
        val base = engineSound ?: return
        val instance = entity.instance ?: return
        if (!isAirborne(entity)) return stopEngine(entity)
        val slot = engineSlots.getOrPut(entity) { (0 until ENGINE_SLOTS).firstOrNull { it !in engineSlots.values } ?: 0 }
        if (entity.aliveTicks < (engineNext[entity] ?: 0L)) return
        instance.playSound(engineVariant(base, slot), entity)
        engineNext[entity] = entity.aliveTicks + engineSoundTicks
    }

    private fun stopEngine(entity: Entity) {
        val slot = engineSlots.remove(entity) ?: return
        engineNext.remove(entity)
        engineSound?.let { entity.instance?.stopSound(SoundStop.named(engineVariant(it, slot).name())) }
    }

    private fun engineVariant(
        base: Sound,
        slot: Int,
    ) = Sound.sound(Key.key("${base.name().asString()}.$slot"), base.source(), base.volume(), base.pitch())

    private fun showControls(player: Player) {
        fun key(id: String) = Component.keybind(id, NamedTextColor.YELLOW)

        fun label(text: String) = Component.text(text, NamedTextColor.GRAY)
        val controls =
            Component
                .text()
                .append(key("key.forward"))
                .append(label(if (turnSpeed > 0f) " fly, look to steer   " else " fly where you look   "))
                .append(key("key.back"))
                .append(label(" reverse   "))
                .append(key("key.jump"))
                .append(label(" climb   "))
                .append(key("key.sneak"))
                .append(label(" descend   "))
                .append(key("key.swapOffhand"))
                .append(label(" exit"))
                .build()
        val times = Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(7), Duration.ofSeconds(1))
        player.showTitle(Title.title(Component.empty(), controls, times))
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
            stopEngine(entity)
            return
        }
        cleanupRuntime(entity)
        super.destroy(entity, attacker, weapon)
    }

    private fun sink(entity: Entity) {
        val instance = entity.instance ?: return
        val next = entity.position.add(0.0, -sinkSpeed, 0.0)
        if (groundHitbox.checkGroundCollision(instance, next, next.yaw, next.pitch, 0f)) {
            destroy(entity)
        } else {
            entity.teleport(next)
        }
    }

    override fun cleanupRuntime(entity: Entity) {
        stopEngine(entity)
        velocities.remove(entity)
        sinking.remove(entity)
        airborne.remove(entity)
    }

    private companion object {
        // per-hull state, cleared in cleanupRuntime; weak so a hull removed another way cannot leak
        val velocities = WeakHashMap<Entity, Vec>()
        val sinking: MutableSet<Entity> = Collections.newSetFromMap(WeakHashMap())
        val airborne = WeakHashMap<Entity, Boolean>()
        val engineSlots = WeakHashMap<Entity, Int>()
        val engineNext = WeakHashMap<Entity, Long>()
        val shownControls: MutableSet<Player> = Collections.newSetFromMap(WeakHashMap())

        const val BRAKING = 2.0
        const val REVERSE_SPEED = 0.5
        const val NOTICE_STEP = 0.01
        const val AIRBORNE_CHECK_TICKS = 10L

        // sounds.json defines this many numbered copies of the engine sound; more hulls than that share the first
        const val ENGINE_SLOTS = 8

        fun approach(
            current: Vec,
            target: Vec,
            step: Double,
        ): Vec {
            val gap = target.sub(current)
            val length = gap.length()
            return if (length <= step) target else current.add(gap.mul(step / length))
        }

        /** Turns the horizontal part of a velocity by [degrees] of yaw, in the direction a positive yaw turns the hull. */
        fun Vec.rotatedBy(degrees: Float): Vec {
            val angle = Math.toRadians(degrees.toDouble())
            return Vec(x * cos(angle) - z * sin(angle), y, x * sin(angle) + z * cos(angle))
        }
    }
}
