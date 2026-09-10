package net.aechronis.combat.objects

import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player

/** Mutable state belonging to one spawned vehicle, separate from its item definition. */
internal class VehicleRuntime(
    val entity: Entity,
    val vehicle: Vehicle,
) {
    private val healthState: Health? = vehicle.health?.fresh()
    private val ammoCapacity: Int? =
        (vehicle as? ArmedVehicle)?.maxAmmo?.also {
            require(it > 0) { "Vehicle maxAmmo must be greater than zero" }
        }
    private var currentAmmo: Int? = ammoCapacity

    val health: Float? get() = healthState?.health
    val maxHealth: Float? get() = healthState?.maxHealth
    val ammo: Int? get() = currentAmmo

    fun restore(
        health: Float?,
        ammo: Int?,
    ) {
        if (health != null && health.isFinite()) healthState?.restore(health)
        if (ammo != null && ammoCapacity != null) currentAmmo = ammo.coerceIn(0, ammoCapacity)
    }

    /** Returns whether this hit depleted the vehicle's health. */
    fun takeDamage(ammoType: AmmoTypes): Boolean = healthState?.takeHp(ammoType) ?: false

    fun refillAmmo() {
        currentAmmo = ammoCapacity
    }

    fun consumeAmmo(): Boolean {
        val remaining = currentAmmo ?: return false
        if (remaining <= 0) return false
        currentAmmo = remaining - 1
        return true
    }
}

internal enum class VehicleSeatRole {
    DRIVER,
    PASSENGER,
}

internal class VehicleRide(
    val player: Player,
    val runtime: VehicleRuntime,
    val seat: Entity,
    val role: VehicleSeatRole,
) {
    val vehicle: Vehicle get() = runtime.vehicle
    val entity: Entity get() = runtime.entity

    private var emptyAmmoFeedbackAt: Long? = null

    fun canReportEmptyAmmo(now: Long): Boolean {
        val previous = emptyAmmoFeedbackAt
        if (previous != null && now - previous < 500L) return false
        emptyAmmoFeedbackAt = now
        return true
    }

    fun clearEmptyAmmoFeedback() {
        emptyAmmoFeedbackAt = null
    }
}

/**
 * Owns vehicle and ride records; callers handle entity spawning, movement, and removal.
 * Each rider has one authoritative record. Driver and ordered passenger views are derived
 * from it, so no parallel occupancy indexes need to be kept in sync. As with the entity
 * mutations they accompany, writes run on the gameplay thread or during paused teardown.
 */
internal object VehicleRegistry {
    private val runtimes = LinkedHashMap<Entity, VehicleRuntime>()
    private val playerRides = LinkedHashMap<Player, VehicleRide>()

    fun register(
        entity: Entity,
        vehicle: Vehicle,
    ): VehicleRuntime {
        require(entity !in runtimes) { "Vehicle entity is already registered" }
        val runtime = VehicleRuntime(entity, vehicle)
        runtimes[entity] = runtime
        return runtime
    }

    fun runtime(entity: Entity): VehicleRuntime? = runtimes[entity]

    fun all(): List<VehicleRuntime> = runtimes.values.toList()

    fun ride(player: Player): VehicleRide? = playerRides[player]

    fun driver(player: Player): VehicleRide? = ride(player)?.takeIf { it.role == VehicleSeatRole.DRIVER }

    fun passenger(player: Player): VehicleRide? = ride(player)?.takeIf { it.role == VehicleSeatRole.PASSENGER }

    fun rides(): List<VehicleRide> = playerRides.values.toList()

    fun driverOf(entity: Entity): VehicleRide? =
        playerRides.values.firstOrNull {
            it.entity === entity && it.role == VehicleSeatRole.DRIVER
        }

    fun passengers(entity: Entity): List<VehicleRide> =
        playerRides.values.filter {
            it.entity === entity && it.role == VehicleSeatRole.PASSENGER
        }

    fun ridesOf(entity: Entity): List<VehicleRide> = playerRides.values.filter { it.entity === entity }

    fun enter(
        player: Player,
        entity: Entity,
        seat: Entity,
        role: VehicleSeatRole,
    ): VehicleRide {
        val runtime = requireNotNull(runtimes[entity]) { "Vehicle entity must be registered before entering" }
        require(player !in playerRides) { "Player is already riding a vehicle" }
        when (role) {
            VehicleSeatRole.DRIVER -> require(driverOf(entity) == null) { "Vehicle already has a driver" }
            VehicleSeatRole.PASSENGER ->
                require(passengers(entity).size < runtime.vehicle.seatOffsets.size - 1) {
                    "Vehicle has no free passenger seats"
                }
        }
        val ride = VehicleRide(player, runtime, seat, role)
        playerRides[player] = ride
        return ride
    }

    /** Detach first so callbacks from subsequent seat removal cannot see an active ride. */
    fun leave(player: Player): VehicleRide? = playerRides.remove(player)

    fun remove(entity: Entity): VehicleRuntime? {
        require(playerRides.values.none { it.entity === entity }) { "Vehicle riders must leave before removal" }
        return runtimes.remove(entity)
    }

    fun clear() {
        playerRides.clear()
        runtimes.clear()
    }
}
