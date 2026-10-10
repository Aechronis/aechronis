package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.constants.Tags
import net.aechronis.combat.events.VehicleSpawnEvent
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.utils.Message
import net.aechronis.combat.utils.Mounts
import net.aechronis.combat.utils.rotatePoint
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.title.Title
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.timer.TaskSchedule

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
        if (Combat.playerStates[player]?.placeTask != null) return false // already placing
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

        Combat.playerStates.getOrCreate(player).placeTask =
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
                        Combat.playerStates[player]?.cancelPlacement()
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

                        Combat.playerStates[player]?.cancelPlacement()
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

    private val crew = VehicleCrew(this, ::getSeatWorldPos, ::getGunnerSeatWorldPos)

    /** Right-click boarding always chooses the first free crew seat in definition order. */
    fun board(
        player: Player,
        entity: Entity,
    ): Boolean = crew.board(player, entity)

    internal fun switchSeat(
        player: Player,
        index: Int,
    ): Boolean = crew.switchSeat(player, index)

    open fun onEnter(
        player: Player,
        entity: Entity,
    ) {
        if (!canEnterAsDriver(player, entity)) return
        crew.mount(player, entity, seats.first { it.role.drives })
    }

    open fun onExit(player: Player) {
        val ride = VehicleRegistry.driver(player)?.takeIf { it.vehicle === this } ?: return
        crew.leave(ride)
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
            if (player.position.distanceSquared(position) > 1.0e-8) VehicleCrew.moveStandingDriver(player, position)
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
            if (ride.definition.standing) VehicleCrew.moveStandingDriver(ride.player, position) else ride.player.teleport(position)
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
        crew.mount(player, entity, gunnerSeats[stationIndex])
    }

    open fun onGunnerExit(player: Player) {
        val ride = VehicleRegistry.gunner(player)?.takeIf { it.vehicle === this } ?: return
        crew.leave(ride)
    }

    protected fun canEnterAsGunner(
        player: Player,
        entity: Entity,
        stationIndex: Int,
    ): Boolean = crew.canEnterAsGunner(player, entity, stationIndex)

    protected fun canEnterAsDriver(
        player: Player,
        entity: Entity,
    ): Boolean = crew.canEnterAsDriver(player, entity)

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
        val magazine = ride.runtime.magazine ?: return
        val duration = ammoReloadTime(ride.entity).coerceAtLeast(0L)
        updateVehicleReload(player, ride, magazine, armedVehicle.ammo, duration, now)
    }

    protected fun hasReadyAmmo(
        player: Player,
        entity: Entity,
    ): Boolean {
        val armedVehicle = this as? ArmedVehicle ?: return false
        val magazine = VehicleRegistry.runtime(entity)?.magazine ?: return false
        val operator = VehicleRegistry.ride(player)?.takeIf { it.entity === entity && it.role.usesWeapon } ?: return false
        return hasReadyVehicleAmmo(player, operator, magazine, armedVehicle.ammo)
    }

    /** Starts reloading after the last round, or after every shot for single-shot weapons. */
    protected fun consumeAmmo(
        entity: Entity,
        reloadAfterShot: Boolean = false,
    ): Boolean {
        val magazine = VehicleRegistry.runtime(entity)?.magazine ?: return false
        val armedVehicle = this as? ArmedVehicle ?: return false
        val player = if (magazine.ammo == 1) VehicleRegistry.weaponOperator(entity)?.player else null
        return magazine.consume(
            now = System.currentTimeMillis(),
            hasReserve = player != null && armedVehicle.ammo[player] > 0,
            shotRecovery = if (reloadAfterShot) ammoReloadTime(entity) else null,
        )
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
        removeRuntimeEntity(entity)
    }

    protected open fun cleanupRuntime(entity: Entity) = Unit

    /** Invoked once by the registry after riders and the registered runtime are detached. */
    internal fun releaseRuntime(entity: Entity) = cleanupRuntime(entity)

    protected fun removeRuntimeEntity(entity: Entity) {
        VehicleRegistry.ridesOf(entity).forEach { exit(it.player) }
        try {
            VehicleRegistry.remove(entity)
        } finally {
            entity.remove()
        }
    }

    companion object {
        /** Ejects riders and removes every vehicle entity without triggering explosions. */
        @Synchronized
        internal fun shutdown() = VehicleCrew.shutdown()

        /** Routes a normal exit through the vehicle's occupied station hook. */
        internal fun exit(player: Player) = VehicleCrew.exit(player)

        fun isVehicleOccupant(player: Player): Boolean = VehicleCrew.activeRide(player) != null

        fun drivenBy(player: Player): Vehicle? = VehicleRegistry.driver(player)?.vehicle

        // true while the player rides a vehicle that protects its occupants from damage
        fun isProtectedOccupant(player: Player): Boolean = VehicleCrew.activeRide(player)?.isProtected == true

        // center of the protecting vehicle hitbox to use when AI aims at it
        fun protectedVehicleAimPosition(player: Player): Pos? {
            val ride = VehicleCrew.activeRide(player) ?: return null
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
        fun reconcileOccupants() = VehicleCrew.reconcileOccupants()

        fun reconcileOccupant(player: Player) = VehicleCrew.reconcileOccupant(player)

        /** Called when a tracked vehicle body, seat, or player is despawned/moved between instances. */
        fun invalidateEntity(entity: Entity) = VehicleCrew.invalidateEntity(entity)

        fun hasActiveDriver(entity: Entity): Boolean = VehicleCrew.hasActiveDriver(entity)

        internal fun isForcedExit(player: Player): Boolean = VehicleCrew.isForcedExit(player)

        fun getEntityAmmo(entity: Entity): Int? = VehicleRegistry.runtime(entity)?.ammo
    }
}
