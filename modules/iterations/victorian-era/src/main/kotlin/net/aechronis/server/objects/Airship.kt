package net.aechronis.server.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.objects.AnimatedPart
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.VehicleSeat
import net.aechronis.combat.objects.VehicleSeatRole
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A steerable balloon. Its elevatorman still climbs and descends, while a helmsman sets the throttle
 * (forward and backward), which stays where it is left, and steers (left and right) while under way.
 * Without a helmsman the engine stops and the airship slows to a halt.
 */
class Airship(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    model: String = "${Tags.NAMESPACE}:$name",
    scale: Double,
    hitbox: Hitbox,
    health: Health,
    placeTime: Long = 3000,
    climbSpeed: Double = 0.15,
    descentSpeed: Double = 0.15,
    sinkSpeed: Double = 0.05,
    verticalAcceleration: Double = 0.005,
    /** Blocks per tick at full throttle ahead. */
    val maxSpeed: Double = 0.25,
    /** The share of full throttle available astern. */
    val maxReverseThrottle: Double = 0.5,
    /** Throttle change per tick while forward or backward is held; full throttle takes 1 / this many ticks. */
    val throttleStep: Double = 0.025,
    /** Change in horizontal speed per tick. */
    val horizontalAcceleration: Double = 0.005,
    /** Degrees per tick at full speed; the airship turns proportionally slower below it and not at all when stopped. */
    val turnSpeed: Float = 1.5f,
    seat: VehicleSeat = VehicleSeat("elevatorman", "Elevatorman", VehicleSeatRole.DRIVER),
    val helmSeat: VehicleSeat = VehicleSeat("helmsman", "Helmsman", VehicleSeatRole.CREW),
    invisibleWhileRiding: Boolean = false,
    invulnerableWhileRiding: Boolean = false,
    animatedParts: List<AnimatedPart> = emptyList(),
    collisionHitbox: ShulkerHitbox = ShulkerHitbox.fromHitbox(hitbox),
    modelScale: Vec = Vec(scale),
) : Balloon(
        name = name,
        itemName = itemName,
        itemLore = itemLore,
        itemModel = itemModel,
        model = model,
        scale = scale,
        hitbox = hitbox,
        health = health,
        placeTime = placeTime,
        climbSpeed = climbSpeed,
        descentSpeed = descentSpeed,
        sinkSpeed = sinkSpeed,
        verticalAcceleration = verticalAcceleration,
        seat = seat,
        crewSeats = listOf(helmSeat),
        invisibleWhileRiding = invisibleWhileRiding,
        invulnerableWhileRiding = invulnerableWhileRiding,
        animatedParts = animatedParts,
        collisionHitbox = collisionHitbox,
        modelScale = modelScale,
    ) {
    private class Helm {
        // From -maxReverseThrottle (full astern) to 1 (full ahead).
        var throttle = 0.0
        var speed = 0.0

        // -1 steers left, 1 steers right.
        var rudder = 0
    }

    private val helms = HashMap<Entity, Helm>()

    init {
        require(helmSeat.role == VehicleSeatRole.CREW) { "Airship helm must be a crew seat" }
        for ((label, value) in listOf(
            "maxSpeed" to maxSpeed,
            "throttleStep" to throttleStep,
            "horizontalAcceleration" to horizontalAcceleration,
        )) {
            require(value.isFinite() && value > 0.0) { "Airship $label must be positive and finite" }
        }
        require(maxReverseThrottle in 0.0..1.0) { "Airship maxReverseThrottle must be between 0 and 1" }
        require(turnSpeed.isFinite() && turnSpeed >= 0f) { "Airship turnSpeed must be finite and not negative" }
    }

    override fun crewTelemetry(player: Player): String? {
        val helm = crewEntity(player, helmSeat)?.let { helms[it] } ?: return super.crewTelemetry(player)
        return String.format(
            Locale.ROOT,
            "[%.1f blocks/s | %.0f%% throttle]",
            abs(helm.speed) * TICKS_PER_SECOND,
            helm.throttle * 100,
        )
    }

    override fun spawn(
        instance: Instance,
        pos: Pos,
    ): Entity = super.spawn(instance, pos).also { helms[it] = Helm() }

    override fun cleanupRuntime(entity: Entity) {
        helms.remove(entity)
        super.cleanupRuntime(entity)
    }

    override fun onGunnerEnter(
        player: Player,
        entity: Entity,
        stationIndex: Int,
    ) {
        super.onGunnerEnter(player, entity, stationIndex)
        if (crewEntity(player, helmSeat) === entity) frameCrewCamera(player, helmSeat)
    }

    override fun onGunnerExit(player: Player) {
        val entity = crewEntity(player, helmSeat)
        try {
            super.onGunnerExit(player)
        } finally {
            entity?.let { helms[it] }?.let {
                it.throttle = 0.0
                it.rudder = 0
            }
            restoreCrewCamera(player)
        }
    }

    /** Reads the helmsman's controls; the hull answers them on its next tick. */
    override fun onGunnerTick(player: Player) {
        crewEntity(player, helmSeat)?.let { helms[it] }?.let { helm ->
            val input = KeyPressListener.playerInputEvent[player]
            val ahead = input?.isHoldingForwardKey == true
            val astern = input?.isHoldingBackwardKey == true
            if (ahead && !astern) helm.throttle = (helm.throttle + throttleStep).coerceAtMost(1.0)
            if (astern && !ahead) helm.throttle = (helm.throttle - throttleStep).coerceAtLeast(-maxReverseThrottle)
            val left = input?.isHoldingLeftKey == true
            val right = input?.isHoldingRightKey == true
            helm.rudder =
                when {
                    left && !right -> -1
                    right && !left -> 1
                    else -> 0
                }
        }
        super.onGunnerTick(player)
    }

    override fun onHullTick(entity: Entity) {
        val helm = helms[entity] ?: return
        val instance = entity.instance ?: return
        helm.speed += (helm.throttle * maxSpeed - helm.speed).coerceIn(-horizontalAcceleration, horizontalAcceleration)
        if (helm.speed == 0.0) return
        val position = entity.position
        val yaw = position.yaw + helm.rudder * turnSpeed * (abs(helm.speed) / maxSpeed).toFloat()
        val yawRadians = Math.toRadians(yaw.toDouble())
        val next = position.add(-sin(yawRadians) * helm.speed, 0.0, cos(yawRadians) * helm.speed).withYaw(yaw)
        // Like a car, a blocked airship stops; the throttle then builds speed again against the obstacle.
        if (canOccupy(instance, next, entity)) entity.teleport(next) else helm.speed = 0.0
    }

    companion object {
        private const val TICKS_PER_SECOND = 20

        /**
         * A propeller turned by the crew or engine at [fullThrottleRpm] times the throttle, reversing astern.
         * Its model is centred on the hub, turning about the vehicle-local Z shaft at [offset].
         */
        fun propeller(
            model: String,
            offset: Vec,
            fullThrottleRpm: Double,
        ): AnimatedPart {
            require(fullThrottleRpm.isFinite() && fullThrottleRpm > 0.0) { "Propeller fullThrottleRpm must be positive and finite" }
            val radiansPerTick = fullThrottleRpm * 2.0 * PI / 60.0 / TICKS_PER_SECOND
            return AnimatedPart(
                model = model,
                offset = offset,
                initialPose = AnimatedPart.Pose(axis = Vec(0.0, 0.0, 1.0)),
                motion =
                    AnimatedPart.Motion { context, previous ->
                        val throttle = (context.vehicle as Airship).helms[context.entity]?.throttle ?: 0.0
                        // A 4-pi period preserves quaternion signs through a full revolution.
                        previous.copy(angle = (previous.angle + throttle * radiansPerTick) % (4.0 * PI))
                    },
            )
        }

        /**
         * A rudder that swings about its hinge [axis] at [offset] as the helmsman steers, easing to
         * [maxDeflectionDegrees] and back. Its model is centred on a point of the hinge line.
         * Aft rudders swing their trailing edge toward the turn; [forward] ones, ahead of the pivot, swing it away.
         */
        fun rudder(
            model: String,
            offset: Vec,
            axis: Vec = Vec(0.0, 1.0, 0.0),
            forward: Boolean = false,
            maxDeflectionDegrees: Double = 30.0,
            degreesPerTick: Double = 3.0,
        ): AnimatedPart {
            require(maxDeflectionDegrees.isFinite() && maxDeflectionDegrees in 0.0..90.0) { "Rudder deflection must be 0 to 90 degrees" }
            require(degreesPerTick.isFinite() && degreesPerTick > 0.0) { "Rudder speed must be positive and finite" }
            // About a vertical hinge, a negative angle swings an aft trailing edge to the left, toward a left turn.
            val radiansPerRudder = Math.toRadians(maxDeflectionDegrees) * if (forward) -1.0 else 1.0
            val step = Math.toRadians(degreesPerTick)
            return AnimatedPart(
                model = model,
                offset = offset,
                initialPose = AnimatedPart.Pose(axis = axis),
                motion =
                    AnimatedPart.Motion { context, previous ->
                        val rudder = (context.vehicle as Airship).helms[context.entity]?.rudder ?: 0
                        val target = rudder * radiansPerRudder
                        previous.copy(angle = previous.angle + (target - previous.angle).coerceIn(-step, step))
                    },
            )
        }
    }
}
