package net.aechronis.server.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.objects.AnimatedPart
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.objects.VehicleSeat
import net.aechronis.combat.objects.VehicleSeatRole
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * A free balloon cannot steer: its elevatorman only climbs (forward) or descends (backward).
 * Subclasses may add [crewSeats] after the elevatorman and move the hull in [onHullTick].
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
    /** Blocks per tick while the forward key is held. */
    val climbSpeed: Double = 0.15,
    /** Blocks per tick while the backward key is held. */
    val descentSpeed: Double = 0.15,
    /** Blocks per tick an unattended balloon sinks, so it never strands out of reach. */
    val sinkSpeed: Double = 0.05,
    /** Change in vertical speed per tick. */
    val verticalAcceleration: Double = 0.005,
    seat: VehicleSeat = VehicleSeat("elevatorman", "Elevatorman", VehicleSeatRole.DRIVER),
    crewSeats: List<VehicleSeat> = emptyList(),
    invisibleWhileRiding: Boolean = false,
    invulnerableWhileRiding: Boolean = false,
    animatedParts: List<AnimatedPart> = emptyList(),
    collisionHitbox: ShulkerHitbox = ShulkerHitbox.fromHitbox(hitbox),
    modelScale: Vec = Vec(scale),
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
        listOf(seat) + crewSeats,
        invisibleWhileRiding,
        invulnerableWhileRiding,
        animatedParts,
        collisionHitbox,
        modelScale,
    ) {
    private class Runtime {
        var verticalSpeed = 0.0

        // Each move is chosen a tick ahead, so the collision shulkers can lead by exactly it.
        var plannedMove = 0.0

        // An unattended balloon resting on something rechecks below it only this often.
        var restTicks = 0
    }

    private val runtimes = HashMap<Entity, Runtime>()

    override fun crewTelemetry(player: Player): String? {
        val runtime = driverEntity(player)?.let { runtimes[it] } ?: return null
        return String.format(Locale.ROOT, "[%+.1f blocks/s vertical]", runtime.verticalSpeed * TICKS_PER_SECOND)
    }

    init {
        for ((label, speed) in listOf("climbSpeed" to climbSpeed, "descentSpeed" to descentSpeed, "sinkSpeed" to sinkSpeed)) {
            require(speed.isFinite() && speed > 0.0) { "Balloon $label must be positive and finite" }
        }
        require(verticalAcceleration.isFinite() && verticalAcceleration > 0.0) {
            "Balloon verticalAcceleration must be positive and finite"
        }
    }

    override fun canPlaceAt(
        instance: Instance,
        pos: Pos,
    ): Boolean = canOccupy(instance, pos.add(0.0, hitbox.getGroundOffset(), 0.0))

    override fun spawn(
        instance: Instance,
        pos: Pos,
    ): Entity = super.spawn(instance, pos).also { runtimes[it] = Runtime() }

    override fun onEnter(
        player: Player,
        entity: Entity,
    ) {
        super.onEnter(player, entity)
        if (driverEntity(player) === entity) frameCrewCamera(player)
    }

    // The registry removes a balloon only after its riders have exited, which restores their camera.
    override fun onExit(player: Player) {
        try {
            super.onExit(player)
        } finally {
            restoreCrewCamera(player)
        }
    }

    override fun cleanupRuntime(entity: Entity) {
        runtimes.remove(entity)
    }

    override fun onTick(player: Player) {
        val entity = driverEntity(player) ?: return
        val runtime = runtimes[entity] ?: return
        val input = KeyPressListener.playerInputEvent[player]
        val climbing = input?.isHoldingForwardKey == true
        val descending = input?.isHoldingBackwardKey == true
        val targetSpeed =
            when {
                climbing && !descending -> climbSpeed
                descending && !climbing -> -descentSpeed
                else -> 0.0
            }
        step(entity, runtime, targetSpeed)
        onHullTick(entity)
        super.onTick(player)
    }

    override fun onUnoccupiedTick(entity: Entity) {
        val runtime = runtimes[entity] ?: return
        onHullTick(entity)
        if (runtime.plannedMove == 0.0 && runtime.restTicks > 0) {
            runtime.restTicks--
            return
        }
        if (!step(entity, runtime, -sinkSpeed)) runtime.restTicks = REST_RECHECK_TICKS
    }

    /** Runs once per tick for each live balloon, after its vertical move and before the elevatorman follows the hull. */
    protected open fun onHullTick(entity: Entity) = Unit

    // Clients place the shulkers a tick late, so leading them by the next climb puts them under a carried
    // rider. Descending shulkers never lead: a lagging one rests at or above the server's floor, where
    // a dropping rider landing on it is never pushed back up.
    override fun collisionLead(entity: Entity): Vec = Vec(0.0, max(0.0, runtimes[entity]?.plannedMove ?: 0.0), 0.0)

    /** Lands before crew are released for shutdown, so nobody is left in mid-air. */
    override fun prepareForShutdown(entity: Entity) {
        runtimes[entity]?.let {
            it.verticalSpeed = 0.0
            it.plannedMove = 0.0
        }
        landingPosition(entity)?.let { entity.teleport(it) }
        super.prepareForShutdown(entity)
    }

    /**
     * Makes the move planned last tick, then eases toward [targetSpeed] to plan the next one.
     * Returns whether a move is planned; false when holding still or resting on something.
     */
    private fun step(
        entity: Entity,
        runtime: Runtime,
        targetSpeed: Double,
    ): Boolean {
        val instance = entity.instance ?: return false
        if (runtime.plannedMove != 0.0) {
            val target = entity.position.add(0.0, runtime.plannedMove, 0.0)
            // The world may have changed since the move was planned.
            if (canOccupy(instance, target, entity)) entity.teleport(target) else runtime.verticalSpeed = 0.0
        }
        val speed =
            runtime.verticalSpeed +
                (targetSpeed - runtime.verticalSpeed).coerceIn(-verticalAcceleration, verticalAcceleration)
        val move = clearMove(instance, entity, speed)
        // A shortened move settles against the obstruction and stops there.
        runtime.verticalSpeed = if (move == speed) speed else 0.0
        runtime.plannedMove = move
        return move != 0.0
    }

    /** The whole of [speed] when clear, otherwise the distance to flush contact with the obstruction. */
    private fun clearMove(
        instance: Instance,
        entity: Entity,
        speed: Double,
    ): Double {
        if (speed == 0.0) return 0.0
        val position = entity.position
        if (canOccupy(instance, position.add(0.0, speed, 0.0), entity)) return speed
        var clear = 0.0
        var blocked = 1.0
        repeat(CONTACT_SEARCH_STEPS) {
            val fraction = (clear + blocked) / 2
            if (canOccupy(instance, position.add(0.0, speed * fraction, 0.0), entity)) clear = fraction else blocked = fraction
        }
        return (speed * clear).takeIf { abs(it) >= MIN_MOVE } ?: 0.0
    }

    /** The lowest clear height with the basket on a whole block, searching down from the current pose. */
    private fun landingPosition(entity: Entity): Pos? {
        val instance = entity.instance ?: return null
        val position = entity.position
        val bottomOffset = hitbox.getBottomOffset()
        var landing: Pos? = null
        var bottom = floor(position.y + bottomOffset)
        while (true) {
            // Clearance fails below the world, so the search always ends.
            val candidate = position.withY(bottom - bottomOffset)
            if (!canOccupy(instance, candidate, entity)) return landing
            landing = candidate
            bottom -= 1.0
        }
    }

    /** Whether the hull fits at [position] clear of blocks and of other vehicles than [entity]. */
    protected fun canOccupy(
        instance: Instance,
        position: Pos,
        entity: Entity? = null,
    ): Boolean = hasSolidClearance(instance, position) && hasVehicleClearance(instance, position, entity)

    companion object {
        private const val REST_RECHECK_TICKS = 20
        private const val TICKS_PER_SECOND = 20

        // Halving the blocked move eight times lands within 1/256 of it of the obstruction.
        private const val CONTACT_SEARCH_STEPS = 8
        private const val MIN_MOVE = 1.0e-3
    }
}
