package net.aechronis.combat.objects

import net.aechronis.combat.utils.preparePacketBundle
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Metadata
import net.minestom.server.entity.MetadataDef
import net.minestom.server.entity.Player
import net.minestom.server.entity.RelativeFlags
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.network.packet.server.ServerPacket
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket
import java.util.concurrent.ConcurrentHashMap

/** Mutable state belonging to one spawned vehicle, separate from its item definition. */
internal class VehicleRuntime(
    val entity: Entity,
    val vehicle: Vehicle,
) {
    private val animatedParts = ArrayList<AnimatedPartRuntime>()
    private val hiddenParts = HashMap<Player, String>()
    private val displays = linkedSetOf(entity)
    private val pendingDisplayMovement = ConcurrentHashMap.newKeySet<Entity>()
    private val carryOrigins = HashMap<Player, Pos>()

    @Volatile
    private var carriedPlayers: Set<Player> = emptySet()

    fun addDisplay(display: VehicleDisplayEntity) {
        displays.add(display)
    }

    fun removeDisplay(display: VehicleDisplayEntity) {
        displays.remove(display)
        pendingDisplayMovement.remove(display)
    }

    fun isCarrying(player: Player): Boolean = player in carriedPlayers

    fun hasCarriedPlayers(): Boolean = carriedPlayers.isNotEmpty()

    fun queueDisplayMovement(display: VehicleDisplayEntity) {
        pendingDisplayMovement.add(display)
    }

    fun rememberCarryOrigin(player: Player) {
        carryOrigins[player] = player.position
    }

    fun flushCarriedMovement() {
        val movedDisplays = pendingDisplayMovement.toList()
        pendingDisplayMovement.removeAll(movedDisplays.toSet())
        val orderedDisplays = movedDisplays.sortedBy { it === entity }
        try {
            for (player in carriedPlayers) {
                if (player.isRemoved || player.instance !== entity.instance) continue
                val packets = ArrayList<ServerPacket>()
                // Keep the visible hull with the camera in the final bundle even if
                // an unusually large collision shape needs multiple protocol bundles.
                for (display in orderedDisplays) {
                    if (display.isRemoved || !display.isViewer(player)) continue
                    val position = display.position
                    packets.add(
                        EntityPositionSyncPacket(display.entityId, position, Vec.ZERO, position.yaw, position.pitch, display.isOnGround),
                    )
                }
                val origin = carryOrigins[player]
                val delta = origin?.let { player.position.asVec().sub(it) }
                if (delta != null && delta.lengthSquared() >= 1.0e-12) {
                    packets.add(
                        PlayerPositionAndLookPacket(
                            -1,
                            delta,
                            Vec.ZERO,
                            0f,
                            0f,
                            RelativeFlags.COORD or RelativeFlags.VIEW or RelativeFlags.DELTA_COORD,
                        ),
                    )
                }
                // Reserve room to keep the final hull and player updates together.
                var start = 0
                while (packets.size - start > 4096) {
                    player.sendPacket(preparePacketBundle(player, packets.subList(start, start + 4095)))
                    start += 4095
                }
                if (start < packets.size) {
                    // One framed queue entry prevents other threads' packets from
                    // splitting the bundle or exposing half of the movement to rendering.
                    player.sendPacket(preparePacketBundle(player, packets.subList(start, packets.size)))
                }
            }
        } finally {
            carryOrigins.clear()
        }
    }

    fun setCarriedPlayers(players: Set<Player>) {
        val previous = carriedPlayers
        if (previous == players) return
        carriedPlayers = players.toSet()
        carryOrigins.keys.retainAll(players)
        // Send only on a transition, and only for displays already visible to this player.
        for (player in (previous - players) + (players - previous)) {
            if (player.isRemoved || player.instance !== entity.instance) continue
            for (display in displays) {
                if (!display.isRemoved && display.isViewer(player)) {
                    updateDisplayInterpolation(display, player)
                    if (player !in players && display in pendingDisplayMovement) {
                        val position = display.position
                        player.sendPacket(
                            EntityPositionSyncPacket(
                                display.entityId,
                                position,
                                Vec.ZERO,
                                position.yaw,
                                position.pitch,
                                display.isOnGround,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun removeCarriedPlayer(player: Player) {
        if (player in carriedPlayers) setCarriedPlayers(carriedPlayers - player)
    }

    fun updateDisplayInterpolation(
        display: Entity,
        player: Player,
    ) {
        val meta = display.entityMeta as ItemDisplayMeta
        player.sendPacket(
            EntityMetaDataPacket(
                display.entityId,
                mapOf(
                    MetadataDef.Display.POSITION_ROTATION_INTERPOLATION_DURATION.index() to
                        Metadata.VarInt(if (isCarrying(player)) 0 else meta.posRotInterpolationDuration),
                ),
            ),
        )
    }

    fun isPartVisibleTo(
        player: Player,
        model: String,
    ): Boolean = hiddenParts[player] != model

    /** Hide the operated gun housing without hiding the hull or other animated parts. */
    fun setPartHidden(
        player: Player,
        model: String,
        hidden: Boolean,
    ) {
        if (hidden) hiddenParts[player] = model else hiddenParts.remove(player, model)
        animatedParts.forEach { it.refreshViewers(hiddenParts.isNotEmpty()) }
        // Restoring from the viewer side also works while the operator is mounted.
        if (!hidden && player.instance != null) player.updateViewerRule()
    }

    private val collisionHitbox = VehicleCollisionHitbox(this)
    private var previousAnimationPosition = entity.position
    private var animationAgeTicks = 0L

    fun spawnParts() {
        try {
            vehicle.animatedParts.forEach { animatedParts.add(AnimatedPartRuntime(it, this)) }
            collisionHitbox.update()
        } catch (exception: Exception) {
            VehicleRegistry.remove(entity)
            entity.remove()
            throw exception
        }
    }

    fun updateAnimatedParts() {
        if (animatedParts.isEmpty() || entity.isRemoved || entity.instance == null) return
        val context =
            AnimatedPart.Context(
                vehicle,
                entity,
                VehicleRegistry.driverOf(entity)?.player,
                previousAnimationPosition,
                entity.position,
                vehicle.hitboxRoll(entity),
                ++animationAgeTicks,
            )
        animatedParts.forEach { it.update(context) }
        previousAnimationPosition = context.position
    }

    fun updateCollisionHitbox() = collisionHitbox.update()

    fun refreshCollisionViewers() = collisionHitbox.refreshViewers()

    fun removeCollisionHitbox() = collisionHitbox.remove()

    fun removeParts() {
        setCarriedPlayers(emptySet())
        collisionHitbox.remove()
        animatedParts.forEach { it.remove() }
        animatedParts.clear()
        hiddenParts.clear()
        displays.clear()
        pendingDisplayMovement.clear()
        carryOrigins.clear()
    }

    private val healthState: Health? = vehicle.health?.fresh()
    val magazine: VehicleMagazine? = (vehicle as? ArmedVehicle)?.maxAmmo?.let(::VehicleMagazine)

    val health: Float? get() = healthState?.health
    val maxHealth: Float? get() = healthState?.maxHealth
    val ammo: Int? get() = magazine?.ammo

    fun restore(
        health: Float?,
        ammo: Int?,
    ) {
        if (health != null && health.isFinite()) healthState?.restore(health)
        if (ammo != null) magazine?.restoreAmmo(ammo)
    }

    /** Returns whether this hit depleted the vehicle's health. */
    fun takeDamage(ammoType: AmmoTypes): Boolean = healthState?.takeHp(ammoType) ?: false
}

internal class VehicleRide(
    val player: Player,
    val runtime: VehicleRuntime,
    val seat: Entity,
    val definition: VehicleSeat,
) {
    val vehicle: Vehicle get() = runtime.vehicle
    val entity: Entity get() = runtime.entity
    val role: VehicleSeatRole get() = definition.role
    val seatIndex: Int get() = vehicle.seats.indexOf(definition)
    val stationIndex: Int? get() = vehicle.gunnerSeats.indexOf(definition).takeIf { it >= 0 }
    val isProtected: Boolean get() = definition.protected ?: vehicle.invulnerableWhileRiding

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
 * Each rider has one authoritative record. Seat and control views are derived
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

    fun driver(player: Player): VehicleRide? = ride(player)?.takeIf { it.role.drives }

    fun gunner(player: Player): VehicleRide? = ride(player)?.takeIf { it.role == VehicleSeatRole.GUNNER }

    fun rides(): List<VehicleRide> = playerRides.values.toList()

    fun driverOf(entity: Entity): VehicleRide? =
        playerRides.values.firstOrNull {
            it.entity === entity && it.role.drives
        }

    fun ridesOf(entity: Entity): List<VehicleRide> = playerRides.values.filter { it.entity === entity }

    fun gunners(entity: Entity): List<VehicleRide> = playerRides.values.filter { it.entity === entity && it.role == VehicleSeatRole.GUNNER }

    fun gunnerOf(
        entity: Entity,
        stationIndex: Int,
    ): VehicleRide? =
        playerRides.values.firstOrNull {
            it.entity === entity && it.role == VehicleSeatRole.GUNNER && it.stationIndex == stationIndex
        }

    fun occupant(
        entity: Entity,
        seatId: String,
    ): VehicleRide? = playerRides.values.firstOrNull { it.entity === entity && it.definition.id == seatId }

    fun weaponOperator(entity: Entity): VehicleRide? = ridesOf(entity).firstOrNull { it.role.usesWeapon }

    fun enter(
        player: Player,
        entity: Entity,
        seat: Entity,
        definition: VehicleSeat,
    ): VehicleRide {
        val runtime = requireNotNull(runtimes[entity]) { "Vehicle entity must be registered before entering" }
        require(player !in playerRides) { "Player is already riding a vehicle" }
        require(definition in runtime.vehicle.seats) { "Unknown crew seat" }
        require(occupant(entity, definition.id) == null) { "Crew seat is occupied" }
        val ride = VehicleRide(player, runtime, seat, definition)
        playerRides[player] = ride
        runtime.removeCarriedPlayer(player)
        runtime.refreshCollisionViewers()
        return ride
    }

    /** Detach first so callbacks from subsequent seat removal cannot see an active ride. */
    fun leave(player: Player): VehicleRide? = playerRides.remove(player)?.also { it.runtime.refreshCollisionViewers() }

    fun remove(entity: Entity): VehicleRuntime? {
        require(playerRides.values.none { it.entity === entity }) { "Vehicle riders must leave before removal" }
        val runtime = runtimes.remove(entity) ?: return null
        try {
            runtime.vehicle.releaseRuntime(entity)
        } catch (failure: Throwable) {
            runCatching(runtime::removeParts).onFailure(failure::addSuppressed)
            throw failure
        }
        runtime.removeParts()
        return runtime
    }

    fun clear() {
        playerRides.clear()
        val failures = ArrayList<Throwable>()
        runtimes.keys.toList().forEach { entity ->
            runCatching { remove(entity) }.onFailure(failures::add)
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Vehicle runtime cleanup completed with ${failures.size} failure(s)").apply {
                failures.forEach(::addSuppressed)
            }
        }
    }
}
