package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.constants.Tags
import net.aechronis.combat.events.VehicleSpawnEvent
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.utils.LagCompensation
import net.aechronis.combat.utils.Message
import net.aechronis.combat.utils.VehicleCameraDistance
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.utils.VisibilityRules
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.title.Title
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.timer.TaskSchedule
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

open class Vehicle(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    val model: String = "${Tags.NAMESPACE}:$name",
    val scale: Double,
    val hitbox: Hitbox,
    val health: Health?,
    val placeTime: Long = 3000,
    val seatOffsets: List<Vec> = listOf(Vec.ZERO),
    // hide the occupant from other players while they're riding
    val invisibleWhileRiding: Boolean = true,
    // occupant takes no damage while they're riding
    val invulnerableWhileRiding: Boolean = true,
) : Item(
        name,
        itemName,
        itemLore,
        itemModel,
    ) {
    // ================
    // PLACE FUNCTIONS
    // ================
    fun place(
        player: Player,
        pos: Pos,
    ): Boolean {
        if (Combat.placeTasks[player] != null) return false // already placing
        val instance = player.instance ?: return false
        val spawnEvent = VehicleSpawnEvent(player, this, instance, pos)
        MinecraftServer.getGlobalEventHandler().call(spawnEvent)
        if (spawnEvent.isCancelled) return false
        if (!canPlaceAt(instance, pos)) return false

        // create task
        runPlaceTask(player, pos)

        return true
    }

    protected open fun canPlaceAt(
        instance: Instance,
        pos: Pos,
    ): Boolean {
        val adjustedPosition = pos.add(0.0, hitbox.getGroundOffset(), 0.0)
        return VehicleRegistry.all().none { runtime ->
            val entity = runtime.entity
            val vehicle = runtime.vehicle
            entity.instance === instance &&
                hitbox.intersects(
                    vehicle.hitbox,
                    adjustedPosition,
                    adjustedPosition.yaw,
                    adjustedPosition.pitch,
                    0f,
                    entity.position,
                    entity.position.yaw,
                    entity.position.pitch,
                    vehicle.hitboxRoll(entity),
                )
        }
    }

    private fun runPlaceTask(
        player: Player,
        pos: Pos,
    ) {
        var time = placeTime

        Combat.placeTasks[player] =
            ModuleScheduler
                .buildTask {
                    time -= 100
                    val progress: Double = 1.0 - (time.toDouble() / placeTime.toDouble())

                    // cancel placing if player changes item their holding
                    if (player.itemInMainHand.getTag(Tags.name) != name) {
                        player.showTitle(
                            Title.title(
                                Component.empty(),
                                Component.text("✕").color(TextColor.color(0.5F, 0F, 0F)).shadowColor(ShadowColor.none()),
                                0,
                                2,
                                10,
                            ),
                        )
                        Combat.placeTasks[player]?.cancel()
                        Combat.placeTasks.remove(player)
                        return@buildTask
                    }

                    // successful reload
                    if (time <= 0) {
                        spawn(player, pos)

                        val held = player.itemInMainHand
                        player.itemInMainHand =
                            if (held.amount() <= 1) ItemStack.AIR else held.withAmount(held.amount() - 1)

                        Combat.placeTasks[player]!!.cancel()
                        Combat.placeTasks.remove(player)
                    } else {
                        player.showTitle(
                            Title.title(
                                Component.empty(),
                                Message.progressBar(progress).shadowColor(ShadowColor.none()),
                                0,
                                3,
                                10,
                            ),
                        )
                    }
                }.delay(TaskSchedule.millis(100))
                .repeat(TaskSchedule.millis(100))
                .schedule()
    }

    fun spawn(
        player: Player,
        pos: Pos,
    ): Entity = spawn(requireNotNull(player.instance) { "Player must be in an instance to spawn a vehicle" }, pos)

    /** Spawns a vehicle without consuming an item or requiring a player, for persistence restores. */
    open fun spawn(
        instance: Instance,
        pos: Pos,
    ): Entity {
        val entity = Entity(EntityType.ITEM_DISPLAY)

        // offset y so the bottom of the hitbox sits on the ground
        val adjustedPos = pos.add(0.0, hitbox.getGroundOffset(), 0.0)
        entity.setInstance(instance, adjustedPos)

        val meta = entity.entityMeta as ItemDisplayMeta
        meta.itemStack = ItemStack.of(Material.BONE).withItemModel(model)
        meta.posRotInterpolationDuration = 3
        meta.scale = Vec(scale)
        meta.isHasNoGravity = true

        entity.spawn()

        VehicleRegistry.register(entity, this)

        return entity
    }

    // called when a player enters this vehicle
    open fun onEnter(
        player: Player,
        entity: Entity,
    ) {
        if (!canEnterAsDriver(player, entity)) return
        val instance = entity.instance ?: return
        val seatPos = getSeatWorldPos(entity, 0)
        val seatEntity = Entity(EntityType.ITEM_DISPLAY)
        seatEntity.setInstance(instance, seatPos)

        val meta = seatEntity.entityMeta as ItemDisplayMeta
        meta.itemStack = ItemStack.AIR
        meta.posRotInterpolationDuration = 3
        meta.isHasNoGravity = true

        seatEntity.spawn()
        seatEntity.addPassenger(player)

        VehicleRegistry.enter(player, entity, seatEntity, VehicleSeatRole.DRIVER)
        hideOccupant(player)
        LagCompensation.resetHistory(player)
    }

    // called when a player exits this vehicle
    open fun onExit(player: Player) {
        val ride = VehicleRegistry.driver(player)?.takeIf { it.vehicle === this } ?: return
        // Detach before removing the seat: Minestom removal events reenter invalidation.
        VehicleRegistry.leave(player)
        LagCompensation.resetHistory(player)
        ride.seat.removePassenger(player)
        ride.seat.remove()
        if (!isForcedExit(player)) moveToSafeExit(player, ride.entity)

        revealOccupant(player)
    }

    // called every tick while a driver occupies the vehicle
    open fun onTick(player: Player) {
        val ride = VehicleRegistry.driver(player) ?: return
        val entity = ride.entity
        val inputEvent = KeyPressListener.playerInputEvent[player]
        if (inputEvent?.isHoldingShiftKey == true) {
            onExit(player)
            return
        }

        val playerView = player.position
        ride.seat.teleport(
            getSeatWorldPos(entity, 0).withView(playerView.yaw, playerView.pitch),
        )

        updatePassengerSeats(entity)
    }

    open fun onUnoccupiedTick(entity: Entity) {}

    /**
     * Removes riders and their temporary seat entities before a server shutdown.
     * Subclasses can reset transient movement state before the normal exit logic runs.
     */
    open fun prepareForShutdown(entity: Entity) {
        VehicleRegistry.driverOf(entity)?.let { ride ->
            val seatPosition = getSeatWorldPos(entity, 0)
            ride.seat.teleport(seatPosition)
            ride.player.teleport(seatPosition)
        }
        VehicleRegistry.passengers(entity).forEachIndexed { index, ride ->
            val seatPosition = getSeatWorldPos(entity, index + 1)
            ride.seat.teleport(seatPosition)
            ride.player.teleport(seatPosition)
        }

        VehicleRegistry.passengers(entity).forEach { onPassengerExit(it.player) }
        VehicleRegistry.driverOf(entity)?.let { onExit(it.player) }
    }

    protected fun updatePassengerSeats(entity: Entity) {
        VehicleRegistry.passengers(entity).forEachIndexed { index, ride ->
            val passenger = ride.player
            val passengerInput = KeyPressListener.playerInputEvent[passenger]
            if (passengerInput?.isHoldingShiftKey == true) {
                onPassengerExit(passenger)
            } else {
                val seatPos = getSeatWorldPos(entity, index + 1)
                ride.seat.teleport(seatPos.withYaw(entity.position.yaw))
            }
        }
    }

    internal open fun hitboxRoll(entity: Entity): Float = 0f

    // called when a player enters as a passenger
    open fun onPassengerEnter(
        player: Player,
        entity: Entity,
    ) {
        if (!canEnterAsPassenger(player, entity)) return

        val passengerCount = VehicleRegistry.passengers(entity).size

        // seatOffsets[0] is driver seat, remaining are passengers
        if (passengerCount >= seatOffsets.size - 1) return

        val seatIndex = passengerCount + 1
        val seatPos = getSeatWorldPos(entity, seatIndex)
        val seatEntity = Entity(EntityType.ITEM_DISPLAY)

        val instance = entity.instance ?: return
        seatEntity.setInstance(instance, seatPos)

        val meta = seatEntity.entityMeta as ItemDisplayMeta
        meta.itemStack = ItemStack.AIR
        meta.posRotInterpolationDuration = 3
        meta.isHasNoGravity = true

        seatEntity.spawn()
        seatEntity.addPassenger(player)

        VehicleRegistry.enter(player, entity, seatEntity, VehicleSeatRole.PASSENGER)
        hideOccupant(player)
        LagCompensation.resetHistory(player)
    }

    // called when a passenger exits vehicle
    open fun onPassengerExit(player: Player) {
        val ride = VehicleRegistry.passenger(player)?.takeIf { it.vehicle === this } ?: return
        VehicleRegistry.leave(player)
        LagCompensation.resetHistory(player)
        ride.seat.removePassenger(player)
        ride.seat.remove()
        if (!isForcedExit(player)) moveToSafeExit(player, ride.entity)

        revealOccupant(player)
    }

    /** True when this vehicle can create a new driver seat for [player]. */
    protected fun canEnterAsDriver(
        player: Player,
        entity: Entity,
    ): Boolean {
        reconcileOccupant(player)
        return !isForcedExit(player) &&
            VehicleRegistry.ride(player) == null &&
            VehicleRegistry.runtime(entity)?.vehicle === this &&
            !entity.isRemoved &&
            entity.instance != null &&
            entity.instance === player.instance &&
            player.vehicle == null &&
            !hasActiveDriver(entity)
    }

    /** True when this vehicle can create a new passenger seat for [player]. */
    protected fun canEnterAsPassenger(
        player: Player,
        entity: Entity,
    ): Boolean {
        reconcileOccupant(player)
        return !isForcedExit(player) &&
            VehicleRegistry.ride(player) == null &&
            VehicleRegistry.runtime(entity)?.vehicle === this &&
            !entity.isRemoved &&
            entity.instance != null &&
            entity.instance === player.instance &&
            player.vehicle == null
    }

    private fun hideOccupant(player: Player) {
        if (!invisibleWhileRiding) return
        hiddenOccupants.add(player)
        VisibilityRules.set(player, VISIBILITY_RULE_OWNER) { false }
    }

    private fun revealOccupant(player: Player) {
        if (hiddenOccupants.remove(player)) VisibilityRules.remove(player, VISIBILITY_RULE_OWNER)
    }

    // get world position for a seat
    protected fun getSeatWorldPos(
        entity: Entity,
        seatIndex: Int,
    ): Pos {
        val vehiclePos = entity.position
        val localOffset = seatOffsets.getOrElse(seatIndex) { Vec.ZERO }
        val yawRad = Math.toRadians(vehiclePos.yaw.toDouble())
        val rotatedX = localOffset.x * cos(yawRad) - localOffset.z * sin(yawRad)
        val rotatedZ = localOffset.x * sin(yawRad) + localOffset.z * cos(yawRad)
        return vehiclePos.add(rotatedX, localOffset.y, rotatedZ)
    }

    private fun moveToSafeExit(
        player: Player,
        source: Entity,
    ) {
        val instance = source.instance ?: return
        val sourcePosition = source.position
        val box = player.boundingBox
        val clearance = max(box.width(), box.depth()) + 0.35
        val baseRadius = hitbox.getMaxDistanceFrom(Vec.ZERO) + clearance
        val yOffsets = listOf(0.0, 1.0, -1.0, 2.0)
        val candidates =
            buildList {
                for (radius in listOf(baseRadius, baseRadius + 1.0, baseRadius + 2.0)) {
                    for (step in 0 until 8) {
                        val angle = Math.PI * 2.0 * step / 8.0
                        for (yOffset in yOffsets) {
                            add(
                                Pos(
                                    sourcePosition.x + sin(angle) * radius,
                                    player.position.y + yOffset,
                                    sourcePosition.z + cos(angle) * radius,
                                    player.position.yaw,
                                    player.position.pitch,
                                ),
                            )
                        }
                    }
                }
            }
        val safe = candidates.firstOrNull { candidate -> isSafeExitPosition(player, candidate, instance) }
        if (safe != null) {
            player.teleport(safe)
            return
        }

        hitbox
            .resolveCollision(
                sourcePosition,
                sourcePosition.yaw,
                sourcePosition.pitch,
                hitboxRoll(source),
                player.position,
                box.relativeStart(),
                box.relativeEnd(),
            )?.let { resolved -> player.teleport(resolved.position) }
    }

    private fun isSafeExitPosition(
        player: Player,
        position: Pos,
        instance: Instance,
    ): Boolean {
        val box = player.boundingBox
        val start = box.relativeStart()
        val end = box.relativeEnd()
        for (x in floor(position.x + start.x).toInt()..floor(position.x + end.x).toInt()) {
            for (y in floor(position.y + start.y).toInt()..floor(position.y + end.y).toInt()) {
                for (z in floor(position.z + start.z).toInt()..floor(position.z + end.z).toInt()) {
                    if (instance.getBlock(x, y, z).isSolid) return false
                }
            }
        }
        return VehicleRegistry.all().none { runtime ->
            val entity = runtime.entity
            val vehicle = runtime.vehicle
            entity.instance === instance &&
                vehicle.hitbox.resolveCollision(
                    entity.position,
                    entity.position.yaw,
                    entity.position.pitch,
                    vehicle.hitboxRoll(entity),
                    position,
                    start,
                    end,
                ) != null
        }
    }

    /** Returns the current magazine size for an armed vehicle, or null for an unarmed vehicle. */
    fun getAmmo(entity: Entity): Int? = VehicleRegistry.runtime(entity)?.ammo

    /**
     * Refills an empty vehicle magazine from the driver's inventory.
     * An empty attempt starts the reload but does not fire until the next tick.
     */
    protected fun reloadAmmoIfEmpty(
        player: Player,
        entity: Entity,
    ) {
        val armedVehicle = this as? ArmedVehicle ?: return
        val runtime = VehicleRegistry.runtime(entity) ?: return
        val current = runtime.ammo ?: return
        if (current > 0) return

        if (armedVehicle.ammo[player] == 0) {
            val now = System.currentTimeMillis()
            val ride = VehicleRegistry.driver(player) ?: return
            if (!ride.canReportEmptyAmmo(now)) return
            player.showTitle(
                Title.title(
                    Component.empty(),
                    Component.text("✕").color(TextColor.color(0.5F, 0F, 0F)).shadowColor(ShadowColor.none()),
                    0,
                    10,
                    10,
                ),
            )
            return
        }

        VehicleRegistry.driver(player)?.clearEmptyAmmoFeedback()
        armedVehicle.ammo[player] -= 1
        runtime.refillAmmo()
    }

    /** Called after a successful shot to remove one round from the vehicle magazine. */
    protected fun consumeAmmo(entity: Entity): Boolean = VehicleRegistry.runtime(entity)?.consumeAmmo() == true

    // called when the vehicle takes damage
    open fun takeDamage(
        entity: Entity,
        ammoType: AmmoTypes?,
        amount: Float,
        attacker: Player?,
        weapon: Component? = null,
    ): Boolean {
        if (ammoType != null && VehicleRegistry.runtime(entity)?.takeDamage(ammoType) == true) {
            destroy(entity, attacker, weapon)
            return true
        }
        return false
    }

    // called when vehicle is destroyed
    open fun destroy(
        entity: Entity,
        attacker: Player? = null,
        weapon: Component? = null,
    ) = removeRuntimeEntity(entity)

    /** Removes a live vehicle for module teardown without invoking destruction effects. */
    internal fun unload(entity: Entity) {
        prepareForShutdown(entity)
        cleanupRuntime(entity)
        removeRuntimeEntity(entity)
    }

    protected open fun cleanupRuntime(entity: Entity) = Unit

    protected fun removeRuntimeEntity(entity: Entity) {
        VehicleRegistry.passengers(entity).forEach { onPassengerExit(it.player) }
        VehicleRegistry.driverOf(entity)?.let { onExit(it.player) }
        VehicleRegistry.remove(entity)

        // remove the displayentity
        entity.remove()
    }

    companion object {
        private const val VISIBILITY_RULE_OWNER = "combat:vehicle-occupant"
        private val hiddenOccupants = HashSet<Player>()
        private val forcedExitPlayers = HashSet<Player>()

        /** Ejects riders and removes every vehicle entity without triggering explosions. */
        @Synchronized
        internal fun shutdown() {
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

            listOf<() -> Unit>(
                Drone::shutdownRuntimeState,
                Plane::shutdownRuntimeState,
                Tank::shutdownRuntimeState,
                Car::shutdownRuntimeState,
                VehicleCameraDistance::shutdown,
            ).forEach(::cleanup)

            VehicleRegistry.rides().forEach { ride -> cleanup { ride.seat.remove() } }
            VehicleRegistry.all().forEach { runtime -> cleanup { runtime.entity.remove() } }
            hiddenOccupants.toList().forEach { player -> cleanup { VisibilityRules.remove(player, VISIBILITY_RULE_OWNER) } }
            VehicleRegistry.clear()
            hiddenOccupants.clear()
            forcedExitPlayers.clear()

            if (failures.isNotEmpty()) {
                throw IllegalStateException("Vehicle shutdown completed with ${failures.size} cleanup failure(s)").apply {
                    failures.forEach(::addSuppressed)
                }
            }
        }

        /** Routes a normal exit through the vehicle's driver or passenger hook. */
        internal fun exit(player: Player) {
            val ride = VehicleRegistry.ride(player) ?: return
            when (ride.role) {
                VehicleSeatRole.DRIVER -> ride.vehicle.onExit(player)
                VehicleSeatRole.PASSENGER -> ride.vehicle.onPassengerExit(player)
            }
        }

        fun isVehicleOccupant(player: Player): Boolean = activeRide(player) != null

        // true while the player rides a vehicle that protects its occupants from damage
        fun isProtectedOccupant(player: Player): Boolean = activeRide(player)?.vehicle?.invulnerableWhileRiding == true

        // center of the protecting vehicle hitbox to use when AI aims at it
        fun protectedVehicleAimPosition(player: Player): Pos? {
            val ride = activeRide(player) ?: return null
            val vehicle = ride.vehicle
            if (!vehicle.invulnerableWhileRiding) return null
            val position = ride.entity.position
            return vehicle.hitbox.getWorldCenter(
                position,
                position.yaw,
                position.pitch,
                vehicle.hitboxRoll(ride.entity),
            )
        }

        /** Removes invalid vehicle state without treating it as a player-requested exit. */
        fun reconcileOccupants() {
            VehicleRegistry.rides().forEach { reconcileOccupant(it.player) }
        }

        fun reconcileOccupant(player: Player) {
            if (VehicleRegistry.ride(player) != null && activeRide(player) == null) forceExit(player)
        }

        /** Called when a tracked vehicle body, seat, or player is despawned/moved between instances. */
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

        internal fun isForcedExit(player: Player): Boolean = player in forcedExitPlayers

        private fun activeRide(player: Player): VehicleRide? {
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
                    player.vehicle === seat &&
                    player in seat.passengers
            }
        }

        private fun forceExit(player: Player) {
            val ride = VehicleRegistry.ride(player) ?: return
            if (!forcedExitPlayers.add(player)) return
            try {
                // Subclasses still need the ride while restoring cameras, flight state, and bounds.
                exit(player)
            } finally {
                try {
                    // A subclass may fail before reaching the base exit. Detach before seat removal.
                    VehicleRegistry.leave(player)
                    if (player.vehicle === ride.seat) ride.seat.removePassenger(player)
                    if (!ride.seat.isRemoved) ride.seat.remove()
                } finally {
                    forcedExitPlayers.remove(player)
                    if (hiddenOccupants.remove(player)) VisibilityRules.remove(player, VISIBILITY_RULE_OWNER)
                }
            }
        }

        fun getEntityAmmo(entity: Entity): Int? = VehicleRegistry.runtime(entity)?.ammo
    }
}
