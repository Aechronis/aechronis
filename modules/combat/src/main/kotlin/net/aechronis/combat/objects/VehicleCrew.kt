package net.aechronis.combat.objects

import net.aechronis.combat.utils.LagCompensation
import net.aechronis.combat.utils.VehicleCameraDistance
import net.aechronis.utils.VisibilityRules
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.RelativeFlags
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.attribute.AttributeModifier
import net.minestom.server.entity.attribute.AttributeOperation
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.item.ItemStack

/** Owns crew transitions and their temporary player state; occupancy stays in [VehicleRegistry]. */
internal class VehicleCrew(
    private val vehicle: Vehicle,
    private val seatWorldPosition: (Entity, Int) -> Pos,
    private val gunnerSeatWorldPosition: (Entity, Int) -> Pos,
) {
    fun board(
        player: Player,
        entity: Entity,
    ): Boolean {
        reconcileOccupant(player)
        VehicleRegistry.ridesOf(entity).forEach { reconcileOccupant(it.player) }
        if (!canEnter(player, entity)) return false
        val definition = vehicle.seats.firstOrNull { VehicleRegistry.occupant(entity, it.id) == null }
        if (definition == null) {
            player.sendMessage(
                Component.text("All crew seats are occupied. You can still ride by standing on the vehicle.", NamedTextColor.RED),
            )
            return false
        }
        try {
            enterSeat(player, entity, definition)
        } catch (failure: Throwable) {
            Cleanup(failure).attempt { forceExit(player) }
            throw failure
        }
        return VehicleRegistry.ride(player)?.entity === entity
    }

    private fun enterSeat(
        player: Player,
        entity: Entity,
        definition: VehicleSeat,
    ) {
        if (definition.role.drives) {
            vehicle.onEnter(player, entity)
        } else {
            vehicle.onGunnerEnter(player, entity, vehicle.gunnerSeats.indexOf(definition))
        }
    }

    /** An occupied target never releases the old seat; transfers retain the original hotbar session. */
    fun switchSeat(
        player: Player,
        index: Int,
    ): Boolean {
        val original = VehicleRegistry.ride(player)?.takeIf { it.vehicle === vehicle } ?: return false
        val destination = vehicle.seats.getOrNull(index) ?: return false
        if (original.definition == destination) return true
        VehicleRegistry.occupant(original.entity, destination.id)?.let { reconcileOccupant(it.player) }
        if (VehicleRegistry.occupant(original.entity, destination.id) != null) {
            player.sendMessage(Component.text("${destination.name} is occupied.", NamedTextColor.RED))
            return false
        }
        if (!switchingPlayers.add(player)) return false
        var failure: Throwable? = null
        try {
            exit(player)
            enterSeat(player, original.entity, destination)
            if (VehicleRegistry.ride(player)?.definition == destination) return true
            enterSeat(player, original.entity, original.definition)
            return false
        } catch (exception: Throwable) {
            failure = exception
            Cleanup(exception).attempt {
                forceExit(player)
                enterSeat(player, original.entity, original.definition)
            }
            throw exception
        } finally {
            switchingPlayers.remove(player)
            val cleanup = Cleanup(failure)
            cleanup.attempt { if (VehicleRegistry.ride(player) == null) VehicleSeatHotbar.close(player) }
            cleanup.attempt { VehicleSeatHotbar.refresh(original.entity) }
            cleanup.throwIfFailed()
        }
    }

    fun canEnterAsGunner(
        player: Player,
        entity: Entity,
        stationIndex: Int,
    ): Boolean {
        val definition = vehicle.gunnerSeats.getOrNull(stationIndex) ?: return false
        VehicleRegistry.occupant(entity, definition.id)?.let { reconcileOccupant(it.player) }
        return canEnter(player, entity) && VehicleRegistry.occupant(entity, definition.id) == null
    }

    fun canEnterAsDriver(
        player: Player,
        entity: Entity,
    ): Boolean = canEnter(player, entity) && !hasActiveDriver(entity)

    private fun canEnter(
        player: Player,
        entity: Entity,
    ): Boolean {
        reconcileOccupant(player)
        return !isForcedExit(player) &&
            !player.isDead &&
            VehicleRegistry.ride(player) == null &&
            VehicleRegistry.runtime(entity)?.vehicle === vehicle &&
            !entity.isRemoved &&
            entity.instance != null &&
            entity.instance === player.instance &&
            player.vehicle == null
    }

    fun mount(
        player: Player,
        entity: Entity,
        definition: VehicleSeat,
    ) {
        val instance = entity.instance ?: return
        val index = vehicle.seats.indexOf(definition)
        val gunnerIndex = vehicle.gunnerSeats.indexOf(definition)
        val position = if (gunnerIndex >= 0) gunnerSeatWorldPosition(entity, gunnerIndex) else seatWorldPosition(entity, index)
        val seat = Entity(EntityType.ITEM_DISPLAY)
        try {
            seat.setInstance(instance, position.withView(player.position.yaw, player.position.pitch))
            val meta = seat.entityMeta as ItemDisplayMeta
            meta.itemStack = ItemStack.AIR
            meta.posRotInterpolationDuration = 3
            meta.isHasNoGravity = true
            seat.spawn()
            VehicleRegistry.enter(player, entity, seat, definition)
            if (definition.standing) {
                standingControlFieldViews.putIfAbsent(player, player.fieldViewModifier)
                player.fieldViewModifier = 0f
                standingControlAttributes.forEach { player.getAttribute(it).addModifier(standingControlModifier) }
                player.velocity = Vec.ZERO
                moveStandingDriver(player, position)
            } else {
                seat.addPassenger(player)
            }
            if (definition.invisible ?: vehicle.invisibleWhileRiding) {
                hiddenOccupants.add(player)
                VisibilityRules.set(player, VISIBILITY_RULE_OWNER) { false }
            }
            LagCompensation.resetHistory(player)
            VehicleSeatHotbar.open(player)
            VehicleSeatHotbar.refresh(entity)
        } catch (failure: Throwable) {
            val cleanup = Cleanup(failure)
            cleanup.attempt { forceExit(player) }
            cleanup.attempt { if (!seat.isRemoved) seat.remove() }
            throw failure
        }
    }

    fun leave(ride: VehicleRide) {
        releaseSeat(ride) {
            VehicleExitPosition.move(
                ride.player,
                vehicle,
                ride.entity,
                ride.definition,
            ) { seatWorldPosition(ride.entity, ride.seatIndex) }
        }
    }

    companion object {
        private const val VISIBILITY_RULE_OWNER = "combat:vehicle-occupant"
        private val hiddenOccupants = HashSet<Player>()
        private val forcedExitPlayers = HashSet<Player>()
        private val switchingPlayers = HashSet<Player>()
        private val standingControlFieldViews = HashMap<Player, Float>()

        // Multiplying the total by zero also suppresses sprint and equipment speed bonuses.
        private val standingControlAttributes = listOf(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH, Attribute.GRAVITY)
        private val standingControlModifier =
            AttributeModifier("aechronis:standing_vehicle_control", -1.0, AttributeOperation.ADD_MULTIPLIED_TOTAL)

        /** Complete independent restoration steps without losing the first failure. */
        private class Cleanup(
            var failure: Throwable? = null,
        ) {
            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (exception: Throwable) {
                    val first = failure
                    if (first == null) {
                        failure = exception
                    } else if (first !== exception) {
                        first.addSuppressed(exception)
                    }
                }
            }

            fun throwIfFailed() {
                failure?.let { throw it }
            }
        }

        private fun restoreStandingControl(player: Player) {
            val cleanup = Cleanup()
            standingControlAttributes.forEach { attribute ->
                cleanup.attempt { player.getAttribute(attribute).removeModifier(standingControlModifier) }
            }
            cleanup.attempt { standingControlFieldViews.remove(player)?.let { player.fieldViewModifier = it } }
            cleanup.throwIfFailed()
        }

        fun moveStandingDriver(
            player: Player,
            position: Pos,
        ) {
            // Zero relative view deltas preserve even mouse movement not yet received by the server.
            // Continuous movement must not wait for teleport confirmations, which block incoming look updates.
            player.teleport(position.withView(0f, 0f), Vec.ZERO, null, RelativeFlags.VIEW, false)
        }

        /** Detach once, then attempt every restoration even when a prior step failed. */
        private fun releaseSeat(
            ride: VehicleRide,
            moveToExit: () -> Unit = {},
        ) {
            val player = ride.player
            if (VehicleRegistry.ride(player) !== ride) return
            val cleanup = Cleanup()

            // Removing the seat can reenter lifecycle listeners. They must see no active ride.
            cleanup.attempt { VehicleRegistry.leave(player) }
            if (ride.definition.standing) cleanup.attempt { restoreStandingControl(player) }
            if (ride.role.usesWeapon) {
                cleanup.attempt { ride.runtime.magazine?.cancelReload() }
                cleanup.attempt { player.clearTitle() }
            }
            cleanup.attempt { LagCompensation.resetHistory(player) }
            cleanup.attempt { if (player.vehicle === ride.seat && ride.seat.instance != null) ride.seat.removePassenger(player) }
            cleanup.attempt { if (!ride.seat.isRemoved) ride.seat.remove() }
            if (cleanup.failure == null && !isForcedExit(player) && player !in switchingPlayers) cleanup.attempt(moveToExit)
            cleanup.attempt { if (hiddenOccupants.remove(player)) VisibilityRules.remove(player, VISIBILITY_RULE_OWNER) }
            if (player !in switchingPlayers) cleanup.attempt { VehicleSeatHotbar.close(player) }
            cleanup.attempt { ride.runtime.refreshCollisionViewers() }
            cleanup.attempt { VehicleSeatHotbar.refresh(ride.entity) }
            cleanup.throwIfFailed()
        }

        fun exit(player: Player) {
            val ride = VehicleRegistry.ride(player) ?: return
            if (ride.role.drives) ride.vehicle.onExit(player) else ride.vehicle.onGunnerExit(player)
        }

        private fun forceExit(player: Player) {
            val ride = VehicleRegistry.ride(player) ?: return
            if (!forcedExitPlayers.add(player)) return
            val cleanup = Cleanup()
            try {
                // Subclasses still need the ride while restoring cameras, flight state, and bounds.
                cleanup.attempt { exit(player) }
                // If a hook failed before reaching the base exit, run the same teardown here.
                cleanup.attempt { releaseSeat(ride) }
            } finally {
                forcedExitPlayers.remove(player)
            }
            cleanup.throwIfFailed()
        }

        fun isForcedExit(player: Player): Boolean = player in forcedExitPlayers

        fun activeRide(player: Player): VehicleRide? {
            val ride = VehicleRegistry.ride(player) ?: return null
            val entity = ride.entity
            val seat = ride.seat
            return ride.takeIf {
                VehicleRegistry.runtime(entity) === ride.runtime &&
                    !entity.isRemoved &&
                    !seat.isRemoved &&
                    entity.instance != null &&
                    entity.instance === player.instance &&
                    seat.instance === player.instance &&
                    if (ride.definition.standing) {
                        player.vehicle == null && player.position.distanceSquared(seat.position) < 16.0
                    } else {
                        player.vehicle === seat && player in seat.passengers
                    }
            }
        }

        fun reconcileOccupants() {
            VehicleRegistry.rides().forEach { reconcileOccupant(it.player) }
        }

        fun reconcileOccupant(player: Player) {
            if (VehicleRegistry.ride(player) != null && activeRide(player) == null) forceExit(player)
        }

        fun invalidateEntity(entity: Entity) {
            VehicleRegistry
                .rides()
                .filter { it.entity === entity || it.seat === entity || it.player === entity }
                .forEach { forceExit(it.player) }
        }

        fun hasActiveDriver(entity: Entity): Boolean {
            val ride = VehicleRegistry.driverOf(entity) ?: return false
            reconcileOccupant(ride.player)
            return VehicleRegistry.driverOf(entity)?.let { activeRide(it.player) != null } == true
        }

        fun shutdown() {
            val failures = ArrayList<Throwable>()

            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (exception: Throwable) {
                    failures.add(exception)
                }
            }

            VehicleRegistry.all().forEach { runtime -> cleanup { runtime.vehicle.unload(runtime.entity) } }
            VehicleRegistry.rides().forEach { ride -> cleanup { forceExit(ride.player) } }
            cleanup(VehicleCameraDistance::shutdown)
            VehicleRegistry.rides().forEach { ride -> cleanup { ride.seat.remove() } }
            VehicleRegistry.all().forEach { runtime -> cleanup { runtime.entity.remove() } }
            hiddenOccupants.toList().forEach { player -> cleanup { VisibilityRules.remove(player, VISIBILITY_RULE_OWNER) } }
            standingControlFieldViews.keys.toList().forEach { player -> cleanup { restoreStandingControl(player) } }
            cleanup(VehicleRegistry::clear)
            hiddenOccupants.clear()
            standingControlFieldViews.clear()
            forcedExitPlayers.clear()
            switchingPlayers.clear()
            cleanup(VehicleSeatHotbar::shutdown)

            if (failures.isNotEmpty()) {
                throw IllegalStateException("Vehicle shutdown completed with ${failures.size} cleanup failure(s)").apply {
                    failures.forEach(::addSuppressed)
                }
            }
        }
    }
}
