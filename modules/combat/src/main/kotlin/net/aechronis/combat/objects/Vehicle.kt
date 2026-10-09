package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.constants.Tags
import net.aechronis.combat.events.VehicleSpawnEvent
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.utils.LagCompensation
import net.aechronis.combat.utils.Message
import net.aechronis.combat.utils.Mounts
import net.aechronis.combat.utils.VehicleCameraDistance
import net.aechronis.combat.utils.rotatePoint
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.utils.VisibilityRules
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.title.Title
import net.minestom.server.MinecraftServer
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
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.timer.TaskSchedule
import kotlin.math.abs
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
    seats: List<VehicleSeat> = listOf(VehicleSeat("driver", "Driver", VehicleSeatRole.DRIVER)),
    // hide the occupant from other players while they're riding
    val invisibleWhileRiding: Boolean = true,
    // occupant takes no damage while they're riding
    val invulnerableWhileRiding: Boolean = true,
    animatedParts: List<AnimatedPart> = emptyList(),
    val collisionHitbox: ShulkerHitbox = ShulkerHitbox.fromHitbox(hitbox),
    val modelScale: Vec = Vec(scale),
) : Item(
        name,
        itemName,
        itemLore,
        itemModel,
    ) {
    val animatedParts: List<AnimatedPart> = animatedParts.toList()
    val seats: List<VehicleSeat> = seats.toList()
    internal val gunnerSeats: List<VehicleSeat> = this.seats.filter { it.role == VehicleSeatRole.GUNNER }

    init {
        require(this.seats.size in 1..9) { "Vehicles need between one and nine crew seats" }
        require(
            this.seats
                .map { it.id }
                .distinct()
                .size == this.seats.size,
        ) { "Crew seat IDs must be unique" }
        require(this.seats.count { it.role.drives } == 1) { "Vehicles need exactly one driving position" }
        require(gunnerSeats.map { it.weaponId }.distinct().size == gunnerSeats.size) { "Each gunner must control a different weapon" }
        require(
            modelScale.x.isFinite() &&
                modelScale.x > 0.0 &&
                modelScale.y.isFinite() &&
                modelScale.y > 0.0 &&
                modelScale.z.isFinite() &&
                modelScale.z > 0.0,
        ) { "Vehicle model scale must be positive and finite on every axis" }
    }

    /** Whether live instances of this vehicle are included in vehicle saves. */
    open val persistent: Boolean = true

    /** Whether the driver uses a vehicle-owned camera, viewmodel, and shader clock. */
    open val customDriverView: Boolean = false

    /** Current and maximum health for vehicle telemetry. */
    open fun healthStatus(entity: Entity): Pair<Float, Float>? {
        val runtime = VehicleRegistry.runtime(entity) ?: return null
        return (runtime.health ?: return null) to (runtime.maxHealth ?: return null)
    }

    protected fun driverEntity(player: Player): Entity? = VehicleRegistry.driver(player)?.takeIf { it.vehicle === this }?.entity

    /** Right-click with an item on this vehicle; return true to consume the click instead of boarding. */
    open fun onInteract(
        player: Player,
        entity: Entity,
    ): Boolean = false

    /** Extra action-bar text (e.g. fuel) for the driver; null shows nothing. */
    open fun telemetryText(entity: Entity): String? = null

    /** Everyone riding [entity], driver included. */
    protected fun occupants(entity: Entity): List<Player> = VehicleRegistry.ridesOf(entity).map { it.player }

    /** The crew seat [player] occupies on this vehicle, if any. */
    protected fun seatOf(player: Player): VehicleSeat? = VehicleRegistry.ride(player)?.takeIf { it.vehicle === this }?.definition

    protected fun driverSeat(player: Player): Entity? = VehicleRegistry.driver(player)?.takeIf { it.vehicle === this }?.seat

    /** Checks live vehicle hitboxes, including their current roll, for a movement collision. */
    protected fun intersectsVehicle(
        instance: Instance,
        point: Vec,
        excludedEntity: Entity,
    ): Boolean =
        VehicleRegistry.all().any { runtime ->
            val entity = runtime.entity
            if (entity == excludedEntity || entity.instance != instance) return@any false
            val position = entity.position
            runtime.vehicle.hitbox.containsPoint(
                point,
                position,
                position.yaw,
                position.pitch,
                runtime.vehicle.hitboxRoll(entity),
            ) != null
        }

    // ================
    // PLACE FUNCTIONS
    // ================
    fun place(
        player: Player,
        pos: Pos,
    ): Boolean {
        if (Combat.placeTasks[player] != null) return false // already placing
        if (Mounts.isMounted(player)) {
            Mounts.showBlocked(player)
            return false
        }
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
                        // Another vehicle or obstacle may have entered the area during placement.
                        val instance = player.instance
                        if (instance != null && canPlaceAt(instance, pos)) {
                            spawn(player, pos)

                            val held = player.itemInMainHand
                            player.itemInMainHand =
                                if (held.amount() <= 1) ItemStack.AIR else held.withAmount(held.amount() - 1)
                        } else {
                            player.showTitle(
                                Title.title(
                                    Component.empty(),
                                    Component.text("✕").color(TextColor.color(0.5F, 0F, 0F)).shadowColor(ShadowColor.none()),
                                    0,
                                    2,
                                    10,
                                ),
                            )
                        }

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
        val entity = VehicleDisplayEntity()

        // offset y so the bottom of the hitbox sits on the ground
        val adjustedPos = pos.add(0.0, hitbox.getGroundOffset(), 0.0)
        entity.setInstance(instance, adjustedPos)

        val meta = entity.entityMeta as ItemDisplayMeta
        meta.itemStack = ItemStack.of(Material.BONE).withItemModel(model)
        meta.posRotInterpolationDuration = 3
        meta.scale = modelScale
        meta.isHasNoGravity = true

        entity.spawn()

        VehicleRegistry.register(entity, this).spawnParts()

        return entity
    }

    /** Right-click boarding always chooses the first free crew seat in definition order. */
    fun board(
        player: Player,
        entity: Entity,
    ): Boolean {
        reconcileOccupant(player)
        VehicleRegistry.ridesOf(entity).forEach { reconcileOccupant(it.player) }
        if (!canEnter(player, entity)) return false
        val definition = seats.firstOrNull { VehicleRegistry.occupant(entity, it.id) == null }
        if (definition == null) {
            player.sendMessage(
                Component.text("All crew seats are occupied. You can still ride by standing on the vehicle.", NamedTextColor.RED),
            )
            return false
        }
        try {
            enterSeat(player, entity, definition)
        } catch (failure: Exception) {
            try {
                forceExit(player)
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        return VehicleRegistry.ride(player)?.entity === entity
    }

    private fun enterSeat(
        player: Player,
        entity: Entity,
        definition: VehicleSeat,
    ) {
        if (definition.role.drives) onEnter(player, entity) else onGunnerEnter(player, entity, gunnerSeats.indexOf(definition))
    }

    /** A seat transfer runs on the gameplay thread; an occupied target never releases the old seat. */
    internal fun switchSeat(
        player: Player,
        index: Int,
    ): Boolean {
        val original = VehicleRegistry.ride(player)?.takeIf { it.vehicle === this } ?: return false
        val destination = seats.getOrNull(index) ?: return false
        if (original.definition == destination) return true
        VehicleRegistry.occupant(original.entity, destination.id)?.let { reconcileOccupant(it.player) }
        if (VehicleRegistry.occupant(original.entity, destination.id) != null) {
            player.sendMessage(Component.text("${destination.name} is occupied.", NamedTextColor.RED))
            return false
        }
        if (!switchingPlayers.add(player)) return false
        try {
            exit(player)
            enterSeat(player, original.entity, destination)
            if (VehicleRegistry.ride(player)?.definition == destination) return true
            enterSeat(player, original.entity, original.definition)
            return false
        } catch (failure: Exception) {
            try {
                forceExit(player)
                enterSeat(player, original.entity, original.definition)
            } catch (restoreFailure: Exception) {
                failure.addSuppressed(restoreFailure)
            }
            throw failure
        } finally {
            switchingPlayers.remove(player)
            if (VehicleRegistry.ride(player) == null) VehicleSeatHotbar.close(player)
            VehicleSeatHotbar.refresh(original.entity)
        }
    }

    open fun onEnter(
        player: Player,
        entity: Entity,
    ) {
        if (!canEnterAsDriver(player, entity)) return
        mountSeat(player, entity, seats.first { it.role.drives })
    }

    open fun onExit(player: Player) {
        val ride = VehicleRegistry.driver(player)?.takeIf { it.vehicle === this } ?: return
        leaveSeat(ride)
    }

    private fun mountSeat(
        player: Player,
        entity: Entity,
        definition: VehicleSeat,
    ) {
        val instance = entity.instance ?: return
        val index = seats.indexOf(definition)
        val gunnerIndex = gunnerSeats.indexOf(definition)
        val position = if (gunnerIndex >= 0) getGunnerSeatWorldPos(entity, gunnerIndex) else getSeatWorldPos(entity, index)
        val seat = Entity(EntityType.ITEM_DISPLAY)
        seat.setInstance(instance, position.withView(player.position.yaw, player.position.pitch))
        val meta = seat.entityMeta as ItemDisplayMeta
        meta.itemStack = ItemStack.AIR
        meta.posRotInterpolationDuration = 3
        meta.isHasNoGravity = true
        seat.spawn()
        try {
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
            hideOccupant(player)
            LagCompensation.resetHistory(player)
            VehicleSeatHotbar.open(player)
            VehicleSeatHotbar.refresh(entity)
        } catch (failure: Exception) {
            forceExit(player)
            if (!seat.isRemoved) seat.remove()
            throw failure
        }
    }

    private fun leaveSeat(ride: VehicleRide) {
        val player = ride.player
        // Detach first: removing the entity can reenter lifecycle listeners.
        VehicleRegistry.leave(player)
        try {
            if (ride.definition.standing) restoreStandingControl(player)
            if (ride.role.usesWeapon) {
                ride.runtime.reloadStartedAt = null
                player.clearTitle()
            }
            LagCompensation.resetHistory(player)
            if (player.vehicle === ride.seat && ride.seat.instance != null) ride.seat.removePassenger(player)
            if (!ride.seat.isRemoved) ride.seat.remove()
            if (!isForcedExit(player) && player !in switchingPlayers) moveSeatToExit(player, ride.entity, ride.definition)
        } finally {
            revealOccupant(player)
            if (player !in switchingPlayers) VehicleSeatHotbar.close(player)
            ride.runtime.refreshCollisionViewers()
            VehicleSeatHotbar.refresh(ride.entity)
        }
    }

    // Called after the driving controller has moved the hull.
    open fun onTick(player: Player) {
        val ride = VehicleRegistry.driver(player) ?: return
        if (KeyPressListener.playerInputEvent[player]?.isHoldingShiftKey == true) {
            onExit(player)
            return
        }
        val view = player.position
        val position = getSeatWorldPos(ride.entity, ride.seatIndex).withView(view.yaw, view.pitch)
        ride.seat.teleport(position)
        if (ride.definition.standing) {
            if (player.velocity != Vec.ZERO) player.velocity = Vec.ZERO
            if (player.position.distanceSquared(position) > 1.0e-8) moveStandingDriver(player, position)
        }
    }

    open fun onUnoccupiedTick(entity: Entity) = Unit

    /** Weapon operators tick after hull movement, including without a driver. */
    open fun onGunnerTick(player: Player) {
        val ride = VehicleRegistry.gunner(player)?.takeIf { it.vehicle === this } ?: return
        if (KeyPressListener.playerInputEvent[player]?.isHoldingShiftKey == true) {
            onGunnerExit(player)
            return
        }
        val view = player.position
        ride.seat.teleport(getGunnerSeatWorldPos(ride.entity, requireNotNull(ride.stationIndex)).withView(view.yaw, view.pitch))
    }

    open fun prepareForShutdown(entity: Entity) {
        VehicleRegistry.ridesOf(entity).forEach { ride ->
            // Use the physical crew position, not a scoped camera outside the hull.
            val position = getSeatWorldPos(entity, ride.seatIndex)
            ride.seat.teleport(position)
            if (ride.definition.standing) moveStandingDriver(ride.player, position) else ride.player.teleport(position)
        }
        VehicleRegistry.ridesOf(entity).forEach { exit(it.player) }
    }

    internal open fun hitboxRoll(entity: Entity): Float = 0f

    open fun onGunnerEnter(
        player: Player,
        entity: Entity,
        stationIndex: Int,
    ) {
        if (!canEnterAsGunner(player, entity, stationIndex)) return
        mountSeat(player, entity, gunnerSeats[stationIndex])
    }

    open fun onGunnerExit(player: Player) {
        val ride = VehicleRegistry.gunner(player)?.takeIf { it.vehicle === this } ?: return
        leaveSeat(ride)
    }

    protected fun canEnterAsGunner(
        player: Player,
        entity: Entity,
        stationIndex: Int,
    ): Boolean {
        val definition = gunnerSeats.getOrNull(stationIndex) ?: return false
        VehicleRegistry.occupant(entity, definition.id)?.let { reconcileOccupant(it.player) }
        return canEnter(player, entity) && VehicleRegistry.occupant(entity, definition.id) == null
    }

    protected fun canEnterAsDriver(
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
            VehicleRegistry.runtime(entity)?.vehicle === this &&
            !entity.isRemoved &&
            entity.instance != null &&
            entity.instance === player.instance &&
            player.vehicle == null
    }

    private fun hideOccupant(player: Player) {
        if (!(VehicleRegistry.ride(player)?.definition?.invisible ?: invisibleWhileRiding)) return
        hiddenOccupants.add(player)
        VisibilityRules.set(player, VISIBILITY_RULE_OWNER) { false }
    }

    private fun revealOccupant(player: Player) {
        if (hiddenOccupants.remove(player)) VisibilityRules.remove(player, VISIBILITY_RULE_OWNER)
    }

    protected open fun getSeatWorldPos(
        entity: Entity,
        seatIndex: Int,
    ): Pos {
        val position = entity.position
        return position.add(rotatePoint(seats[seatIndex].offset, position.yaw, position.pitch, hitboxRoll(entity)))
    }

    protected open fun getGunnerSeatWorldPos(
        entity: Entity,
        stationIndex: Int,
    ): Pos = getSeatWorldPos(entity, seats.indexOf(gunnerSeats[stationIndex]))

    /** Prefer a nearby physical deck surface before the hull-wide fallback. */
    private fun moveSeatToExit(
        player: Player,
        entity: Entity,
        definition: VehicleSeat,
    ) {
        val instance = entity.instance ?: return
        definition.exitOffset?.let { offset ->
            val position = entity.position.add(rotatePoint(offset, entity.position.yaw, entity.position.pitch, hitboxRoll(entity)))
            if (isSafeExitPosition(player, position, instance)) {
                player.teleport(position.withView(player.position.yaw, player.position.pitch))
                return
            }
        }
        val station = getSeatWorldPos(entity, seats.indexOf(definition))
        val clearance = max(player.boundingBox.width(), player.boundingBox.depth()) / 2 + 0.05
        val candidates =
            collisionHitbox
                .at(entity.position, hitboxRoll(entity))
                .boxes
                .map { box ->
                    val centerX = (box.min.x + box.max.x) / 2
                    val centerZ = (box.min.z + box.max.z) / 2
                    // A narrow platform collapses to one shared midpoint; deriving
                    // both ends separately can invert the range through rounding.
                    val xRadius = ((box.max.x - box.min.x) / 2 - clearance).coerceAtLeast(0.0)
                    val zRadius = ((box.max.z - box.min.z) / 2 - clearance).coerceAtLeast(0.0)
                    Pos(
                        station.x.coerceIn(centerX - xRadius, centerX + xRadius),
                        box.max.y + 0.001,
                        station.z.coerceIn(centerZ - zRadius, centerZ + zRadius),
                        player.position.yaw,
                        player.position.pitch,
                    )
                }.filter { candidate ->
                    val dx = candidate.x - station.x
                    val dz = candidate.z - station.z
                    dx * dx + dz * dz <= 64.0 && abs(candidate.y - station.y) <= 6.0
                }.sortedBy { it.distanceSquared(station) }
        val safe =
            candidates.firstOrNull { candidate ->
                isSafeExitPosition(player, candidate, instance) &&
                    hitbox.resolveCollision(
                        entity.position,
                        entity.position.yaw,
                        entity.position.pitch,
                        hitboxRoll(entity),
                        candidate,
                        player.boundingBox.relativeStart(),
                        player.boundingBox.relativeEnd(),
                    ) == null
            }
        if (safe != null) player.teleport(safe) else moveToSafeExit(player, entity)
    }

    private fun moveToSafeExit(
        player: Player,
        source: Entity,
    ) {
        val instance = source.instance ?: return
        val sourcePosition = source.position
        val box = player.boundingBox
        val clearance = max(box.width(), box.depth()) + 0.35
        val baseRadius = max(hitbox.getMaxDistanceFrom(Vec.ZERO), collisionHitbox.radius) + clearance
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

        collisionHitbox
            .at(sourcePosition, hitboxRoll(source))
            .resolveCollision(
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
                    if (instance.getBlock(x, y, z).solid()) return false
                }
            }
        }
        return VehicleRegistry.all().none { runtime ->
            val entity = runtime.entity
            val vehicle = runtime.vehicle
            entity.instance === instance &&
                vehicle.collisionHitbox
                    .at(entity.position, vehicle.hitboxRoll(entity))
                    .resolveCollision(position, start, end) != null
        }
    }

    /** Returns the current magazine size for an armed vehicle, or null for an unarmed vehicle. */
    fun getAmmo(entity: Entity): Int? = VehicleRegistry.runtime(entity)?.ammo

    /** Allows a vehicle's active weapon to set the shared magazine's reload duration. */
    protected open fun ammoReloadTime(entity: Entity): Long = (this as? ArmedVehicle)?.reloadTime ?: 0L

    /** Weapon state survives crew changes; only the active operator supplies reserve ammunition. */
    internal fun updateAmmoReload(
        player: Player,
        now: Long = System.currentTimeMillis(),
    ) {
        val armedVehicle = this as? ArmedVehicle ?: return
        val ride = VehicleRegistry.ride(player)?.takeIf { it.vehicle === this && it.role.usesWeapon } ?: return
        val runtime = ride.runtime
        val current = runtime.ammo ?: return
        val duration = ammoReloadTime(ride.entity).coerceAtLeast(0L)
        if (current > 0) {
            if (runtime.nextShotAt >
                now
            ) {
                showReloadProgress(player, (1.0 - (runtime.nextShotAt - now).toDouble() / duration.coerceAtLeast(1)).coerceIn(0.0, 1.0))
            }
            return
        }
        if (armedVehicle.ammo[player] == 0) {
            if (runtime.reloadStartedAt != null) player.clearTitle()
            runtime.reloadStartedAt = null
            return
        }
        val startedAt = runtime.reloadStartedAt ?: now.also { runtime.reloadStartedAt = it }
        val elapsed = now - startedAt
        if (elapsed >= duration) {
            armedVehicle.ammo[player] -= 1
            runtime.refillAmmo()
            runtime.reloadStartedAt = null
            ride.clearEmptyAmmoFeedback()
            player.clearTitle()
        } else {
            showReloadProgress(player, (elapsed.toDouble() / duration).coerceIn(0.0, 1.0))
        }
    }

    private fun showReloadProgress(
        player: Player,
        progress: Double,
    ) {
        player.showTitle(Title.title(Component.empty(), Message.progressBar(progress).shadowColor(ShadowColor.none()), 0, 3, 10))
    }

    protected fun hasReadyAmmo(
        player: Player,
        entity: Entity,
    ): Boolean {
        val armedVehicle = this as? ArmedVehicle ?: return false
        val runtime = VehicleRegistry.runtime(entity) ?: return false
        val operator = VehicleRegistry.ride(player)?.takeIf { it.entity === entity && it.role.usesWeapon } ?: return false
        if (runtime.reloadStartedAt != null || runtime.nextShotAt > System.currentTimeMillis()) return false
        if ((runtime.ammo ?: 0) > 0) return true

        if (armedVehicle.ammo[player] == 0) {
            val now = System.currentTimeMillis()
            if (!operator.canReportEmptyAmmo(now)) return false
            player.showTitle(
                Title.title(
                    Component.empty(),
                    Component.text("✕").color(TextColor.color(0.5F, 0F, 0F)).shadowColor(ShadowColor.none()),
                    0,
                    10,
                    10,
                ),
            )
        }
        return false
    }

    /** Starts reloading after the last round, or after every shot for single-shot weapons. */
    protected fun consumeAmmo(
        entity: Entity,
        reloadAfterShot: Boolean = false,
    ): Boolean {
        val runtime = VehicleRegistry.runtime(entity) ?: return false
        if (!runtime.consumeAmmo()) return false
        val now = System.currentTimeMillis()
        if (reloadAfterShot) runtime.nextShotAt = now + ammoReloadTime(entity)
        if (runtime.ammo == 0) {
            val armedVehicle = this as? ArmedVehicle ?: return true
            val player = VehicleRegistry.weaponOperator(entity)?.player ?: return true
            if (armedVehicle.ammo[player] > 0) runtime.reloadStartedAt = now
        }
        return true
    }

    /** Stable weapon IDs allow saved ammunition to survive seat reordering or model changes. */
    internal open fun snapshotWeaponAmmo(entity: Entity): Map<String, Int> {
        val ammo = VehicleRegistry.runtime(entity)?.ammo ?: return emptyMap()
        return mapOf((seats.firstOrNull { it.role.usesWeapon }?.weaponId ?: "main") to ammo)
    }

    internal open fun restoreWeaponAmmo(
        entity: Entity,
        saved: Map<String, Int>?,
        legacyAmmo: Int?,
    ) {
        val id = seats.firstOrNull { it.role.usesWeapon }?.weaponId ?: "main"
        VehicleRegistry.runtime(entity)?.restore(null, saved?.get(id) ?: legacyAmmo)
    }

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
        VehicleRegistry.ridesOf(entity).forEach { exit(it.player) }
        VehicleRegistry.remove(entity)

        // remove the displayentity
        entity.remove()
    }

    companion object {
        private val standingControlFieldViews = HashMap<Player, Float>()

        // Multiplying the total by zero also suppresses sprint and equipment speed bonuses.
        private val standingControlAttributes = listOf(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH, Attribute.GRAVITY)
        private val standingControlModifier =
            AttributeModifier("aechronis:standing_vehicle_control", -1.0, AttributeOperation.ADD_MULTIPLIED_TOTAL)

        private fun restoreStandingControl(player: Player) {
            standingControlAttributes.forEach { player.getAttribute(it).removeModifier(standingControlModifier) }
            standingControlFieldViews.remove(player)?.let { player.fieldViewModifier = it }
        }

        private fun moveStandingDriver(
            player: Player,
            position: Pos,
        ) {
            // Zero relative view deltas preserve even mouse movement not yet received by the server.
            // Continuous movement must not wait for teleport confirmations, which block incoming look updates.
            player.teleport(position.withView(0f, 0f), Vec.ZERO, null, RelativeFlags.VIEW, false)
        }

        private const val VISIBILITY_RULE_OWNER = "combat:vehicle-occupant"
        private val hiddenOccupants = HashSet<Player>()
        private val forcedExitPlayers = HashSet<Player>()
        private val switchingPlayers = HashSet<Player>()

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
            switchingPlayers.clear()
            VehicleSeatHotbar.shutdown()

            if (failures.isNotEmpty()) {
                throw IllegalStateException("Vehicle shutdown completed with ${failures.size} cleanup failure(s)").apply {
                    failures.forEach(::addSuppressed)
                }
            }
        }

        /** Routes a normal exit through the vehicle's occupied station hook. */
        internal fun exit(player: Player) {
            val ride = VehicleRegistry.ride(player) ?: return
            if (ride.role.drives) ride.vehicle.onExit(player) else ride.vehicle.onGunnerExit(player)
        }

        fun isVehicleOccupant(player: Player): Boolean = activeRide(player) != null

        fun drivenBy(player: Player): Vehicle? = VehicleRegistry.driver(player)?.vehicle

        // true while the player rides a vehicle that protects its occupants from damage
        fun isProtectedOccupant(player: Player): Boolean = activeRide(player)?.isProtected == true

        // center of the protecting vehicle hitbox to use when AI aims at it
        fun protectedVehicleAimPosition(player: Player): Pos? {
            val ride = activeRide(player) ?: return null
            val vehicle = ride.vehicle
            if (!ride.isProtected) return null
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
                    if (ride.definition.standing) {
                        player.vehicle == null && player.position.distanceSquared(seat.position) < 16.0
                    } else {
                        player.vehicle === seat && player in seat.passengers
                    }
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
                    if (player.vehicle === ride.seat && ride.seat.instance != null) ride.seat.removePassenger(player)
                    if (!ride.seat.isRemoved) ride.seat.remove()
                    ride.runtime.refreshCollisionViewers()
                } finally {
                    if (ride.definition.standing) restoreStandingControl(player)
                    forcedExitPlayers.remove(player)
                    if (player !in switchingPlayers) VehicleSeatHotbar.close(player)
                    if (hiddenOccupants.remove(player)) VisibilityRules.remove(player, VISIBILITY_RULE_OWNER)
                }
            }
        }

        fun getEntityAmmo(entity: Entity): Int? = VehicleRegistry.runtime(entity)?.ammo
    }
}
