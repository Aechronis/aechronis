package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.constants.Tags
import net.aechronis.combat.events.VehicleSpawnEvent
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.utils.LagCompensation
import net.aechronis.combat.utils.Message
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
        return entityVehicle.none { (entity, vehicle) ->
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
            MinecraftServer
                .getSchedulerManager()
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

        entityVehicle[entity] = this
        health?.let { entityHealth[entity] = it.fresh() }
        if (this is ArmedVehicle) {
            require(maxAmmo > 0) { "Vehicle maxAmmo must be greater than zero" }
            entityAmmo[entity] = maxAmmo
        }

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

        hideOccupant(player)

        playerVehicle[player] = this
        playerVehicleEntity[player] = entity
        playerSeatEntity[player] = seatEntity
        LagCompensation.resetHistory(player)
    }

    // called when a player exits this vehicle
    open fun onExit(player: Player) {
        LagCompensation.resetHistory(player)
        val vehicleEntity = playerVehicleEntity[player]
        val seatEntity = playerSeatEntity.remove(player)
        if (seatEntity != null) {
            seatEntity.removePassenger(player)
            seatEntity.remove()
        }
        playerVehicle.remove(player)
        playerVehicleEntity.remove(player)
        if (!isForcedExit(player)) vehicleEntity?.let { moveToSafeExit(player, it) }

        revealOccupant(player)
    }

    // called every tick while a driver occupies the vehicle
    open fun onTick(player: Player) {
        val entity = playerVehicleEntity[player] ?: return
        val inputEvent = KeyPressListener.playerInputEvent[player]
        if (inputEvent?.isHoldingShiftKey == true) {
            onExit(player)
            return
        }

        val playerView = player.position
        playerSeatEntity[player]?.teleport(
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
        playerVehicleEntity.entries
            .filter { it.value === entity }
            .forEach { (player, _) ->
                val seatPosition = getSeatWorldPos(entity, 0)
                playerSeatEntity[player]?.teleport(seatPosition)
                player.teleport(seatPosition)
            }
        entityPassengers[entity]
            ?.toList()
            ?.forEachIndexed { index, player ->
                val seatPosition = getSeatWorldPos(entity, index + 1)
                passengerSeatEntity[player]?.teleport(seatPosition)
                player.teleport(seatPosition)
            }

        entityPassengers[entity]?.toList()?.forEach(::onPassengerExit)
        playerVehicleEntity.entries
            .filter { it.value === entity }
            .map { it.key }
            .forEach(::onExit)
    }

    protected fun updatePassengerSeats(entity: Entity) {
        entityPassengers[entity]?.toList()?.forEachIndexed { index, passenger ->
            val passengerInput = KeyPressListener.playerInputEvent[passenger]
            if (passengerInput?.isHoldingShiftKey == true) {
                onPassengerExit(passenger)
            } else {
                val seatEntity = passengerSeatEntity[passenger]
                if (seatEntity != null) {
                    val seatPos = getSeatWorldPos(entity, index + 1)
                    seatEntity.teleport(seatPos.withYaw(entity.position.yaw))
                }
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

        val passengers = entityPassengers.getOrPut(entity) { mutableListOf() }

        // seatOffsets[0] is driver seat, remaining are passengers
        if (passengers.size >= seatOffsets.size - 1) return

        val seatIndex = passengers.size + 1
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

        hideOccupant(player)

        passengers.add(player)
        passengerVehicle[player] = this
        passengerVehicleEntity[player] = entity
        passengerSeatEntity[player] = seatEntity
        LagCompensation.resetHistory(player)
    }

    // called when a passenger exits vehicle
    open fun onPassengerExit(player: Player) {
        LagCompensation.resetHistory(player)
        val vehicleEntity = passengerVehicleEntity.remove(player)
        vehicleEntity?.let { entity ->
            entityPassengers[entity]?.let { passengers ->
                passengers.remove(player)
                if (passengers.isEmpty()) entityPassengers.remove(entity)
            }
        }
        passengerVehicle.remove(player)

        // destroy seat entity
        val seatEntity = passengerSeatEntity.remove(player)
        if (seatEntity != null) {
            seatEntity.removePassenger(player)
            seatEntity.remove()
        }
        if (!isForcedExit(player)) vehicleEntity?.let { moveToSafeExit(player, it) }

        revealOccupant(player)
    }

    /** True when this vehicle can create a new driver seat for [player]. */
    protected fun canEnterAsDriver(
        player: Player,
        entity: Entity,
    ): Boolean {
        reconcileOccupant(player)
        return playerVehicle[player] == null &&
            passengerVehicle[player] == null &&
            entityVehicle[entity] === this &&
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
        return playerVehicle[player] == null &&
            passengerVehicle[player] == null &&
            entityVehicle[entity] === this &&
            !entity.isRemoved &&
            entity.instance != null &&
            entity.instance === player.instance &&
            player.vehicle == null
    }

    private fun hideOccupant(player: Player) {
        if (!invisibleWhileRiding) return
        hiddenOccupants.add(player)
        player.updateViewableRule { false }
    }

    private fun revealOccupant(player: Player) {
        if (hiddenOccupants.remove(player)) player.updateViewableRule { true }
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
        return entityVehicle.none { (entity, vehicle) ->
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
    fun getAmmo(entity: Entity): Int? = entityAmmo[entity]

    /**
     * Refills an empty vehicle magazine from the driver's inventory.
     * An empty attempt starts the reload but does not fire until the next tick.
     */
    protected fun reloadAmmoIfEmpty(
        player: Player,
        entity: Entity,
    ) {
        val armedVehicle = this as? ArmedVehicle ?: return
        val current = entityAmmo[entity] ?: return
        if (current > 0) return

        if (armedVehicle.ammo[player] == 0) {
            val now = System.currentTimeMillis()
            if (now - (emptyAmmoFeedbackAt[player] ?: 0L) < 500L) return
            emptyAmmoFeedbackAt[player] = now
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

        emptyAmmoFeedbackAt.remove(player)
        armedVehicle.ammo[player] -= 1
        entityAmmo[entity] = armedVehicle.maxAmmo
    }

    /** Called after a successful shot to remove one round from the vehicle magazine. */
    protected fun consumeAmmo(entity: Entity): Boolean {
        val current = entityAmmo[entity] ?: return false
        if (current <= 0) return false
        entityAmmo[entity] = current - 1
        return true
    }

    // called when the vehicle takes damage
    open fun takeDamage(
        entity: Entity,
        ammoType: AmmoTypes?,
        amount: Float,
        attacker: Player?,
        weapon: Component? = null,
    ): Boolean {
        val currentHealth = entityHealth[entity] ?: return false
        if (ammoType != null && currentHealth.takeHp(ammoType)) {
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
    ) {
        // Eject all passengers
        entityPassengers.remove(entity)?.forEach { onPassengerExit(it) }

        // eject driver
        for ((player, playerEntity) in playerVehicleEntity.toList()) {
            if (playerEntity == entity) {
                onExit(player)
            }
        }

        // clean up tracking
        entityVehicle.remove(entity)
        entityHealth.remove(entity)
        entityAmmo.remove(entity)

        // remove the displayentity
        entity.remove()
    }

    companion object {
        fun isVehicleOccupant(player: Player): Boolean = activeOccupant(player) != null

        // true while the player rides a vehicle that protects its occupants from damage
        fun isProtectedOccupant(player: Player): Boolean = activeOccupant(player)?.invulnerableWhileRiding == true

        // center of the protecting vehicle hitbox to use when AI aims at it
        fun protectedVehicleAimPosition(player: Player): Pos? {
            val vehicle = activeOccupant(player) ?: return null
            if (!vehicle.invulnerableWhileRiding) return null
            val entity = playerVehicleEntity[player] ?: passengerVehicleEntity[player] ?: return null
            val position = entity.position
            return vehicle.hitbox.getWorldCenter(
                position,
                position.yaw,
                position.pitch,
                vehicle.hitboxRoll(entity),
            )
        }

        /** Removes invalid vehicle state without treating it as a player-requested exit. */
        fun reconcileOccupants() {
            val players =
                buildSet {
                    addAll(playerVehicle.keys)
                    addAll(playerVehicleEntity.keys)
                    addAll(playerSeatEntity.keys)
                    addAll(passengerVehicle.keys)
                    addAll(passengerVehicleEntity.keys)
                    addAll(passengerSeatEntity.keys)
                    entityPassengers.values.forEach(::addAll)
                }
            players.forEach(::reconcileOccupant)
        }

        fun reconcileOccupant(player: Player) {
            if (activeOccupant(player) == null && hasOccupantState(player)) forceExit(player)
        }

        /** Called when a tracked vehicle body, seat, or player is despawned/moved between instances. */
        fun invalidateEntity(entity: Entity) {
            val players =
                buildSet {
                    playerVehicleEntity.forEach { (player, body) -> if (body === entity) add(player) }
                    playerSeatEntity.forEach { (player, seat) -> if (seat === entity) add(player) }
                    passengerVehicleEntity.forEach { (player, body) -> if (body === entity) add(player) }
                    passengerSeatEntity.forEach { (player, seat) -> if (seat === entity) add(player) }
                    if (entity is Player) add(entity)
                }
            players.forEach(::forceExit)
        }

        fun hasActiveDriver(entity: Entity): Boolean {
            playerVehicleEntity
                .filterValues { it === entity }
                .keys
                .toList()
                .forEach(::reconcileOccupant)
            return playerVehicle.keys.any { player ->
                playerVehicleEntity[player] === entity && activeOccupant(player) === playerVehicle[player]
            }
        }

        internal fun isForcedExit(player: Player): Boolean = player in forcedExitPlayers

        private fun activeOccupant(player: Player): Vehicle? {
            val driver = playerVehicle[player]
            val passenger = passengerVehicle[player]
            if (driver != null && passenger != null) return null
            return when {
                driver != null && isValidDriver(player, driver) -> driver
                passenger != null && isValidPassenger(player, passenger) -> passenger
                else -> null
            }
        }

        private fun isValidDriver(
            player: Player,
            vehicle: Vehicle,
        ): Boolean {
            val entity = playerVehicleEntity[player] ?: return false
            val seat = playerSeatEntity[player] ?: return false
            return passengerVehicle[player] == null &&
                passengerVehicleEntity[player] == null &&
                passengerSeatEntity[player] == null &&
                entityVehicle[entity] === vehicle &&
                !entity.isRemoved &&
                !seat.isRemoved &&
                entity.instance != null &&
                entity.instance === player.instance &&
                seat.instance === player.instance &&
                player.vehicle === seat &&
                player in seat.passengers
        }

        private fun isValidPassenger(
            player: Player,
            vehicle: Vehicle,
        ): Boolean {
            val entity = passengerVehicleEntity[player] ?: return false
            val seat = passengerSeatEntity[player] ?: return false
            return playerVehicle[player] == null &&
                playerVehicleEntity[player] == null &&
                playerSeatEntity[player] == null &&
                entityVehicle[entity] === vehicle &&
                entityPassengers[entity]?.contains(player) == true &&
                !entity.isRemoved &&
                !seat.isRemoved &&
                entity.instance != null &&
                entity.instance === player.instance &&
                seat.instance === player.instance &&
                player.vehicle === seat &&
                player in seat.passengers
        }

        private fun hasOccupantState(player: Player): Boolean =
            playerVehicle.containsKey(player) ||
                playerVehicleEntity.containsKey(player) ||
                playerSeatEntity.containsKey(player) ||
                passengerVehicle.containsKey(player) ||
                passengerVehicleEntity.containsKey(player) ||
                passengerSeatEntity.containsKey(player)

        private fun forceExit(player: Player) {
            if (!forcedExitPlayers.add(player)) return
            try {
                playerVehicle[player]?.onExit(player)
                passengerVehicle[player]?.onPassengerExit(player)
                // A corrupt partial state may not have a vehicle object to dispatch through.
                playerSeatEntity.remove(player)?.let { seat ->
                    if (player.vehicle === seat) seat.removePassenger(player)
                    seat.remove()
                }
                passengerSeatEntity.remove(player)?.let { seat ->
                    if (player.vehicle === seat) seat.removePassenger(player)
                    seat.remove()
                }
                playerVehicle.remove(player)
                playerVehicleEntity.remove(player)
                passengerVehicle.remove(player)
                passengerVehicleEntity.remove(player)?.let { entity ->
                    entityPassengers[entity]?.remove(player)
                    if (entityPassengers[entity].isNullOrEmpty()) entityPassengers.remove(entity)
                }
                if (hiddenOccupants.remove(player)) player.updateViewableRule { true }
            } finally {
                forcedExitPlayers.remove(player)
            }
        }

        var playerVehicle: HashMap<Player, Vehicle> = HashMap()
        var playerVehicleEntity: HashMap<Player, Entity> = HashMap()
        var entityVehicle: HashMap<Entity, Vehicle> = HashMap()
        var entityHealth: HashMap<Entity, Health> = HashMap()
        var entityAmmo: HashMap<Entity, Int> = HashMap()

        fun getEntityAmmo(entity: Entity): Int? = entityAmmo[entity]

        val emptyAmmoFeedbackAt: HashMap<Player, Long> = HashMap()

        private val hiddenOccupants = HashSet<Player>()
        private val forcedExitPlayers = HashSet<Player>()

        // driver seat
        val playerSeatEntity: HashMap<Player, Entity> = HashMap()

        // passenger tracking
        val entityPassengers: HashMap<Entity, MutableList<Player>> = HashMap()
        val passengerVehicle: HashMap<Player, Vehicle> = HashMap()
        val passengerVehicleEntity: HashMap<Player, Entity> = HashMap()
        val passengerSeatEntity: HashMap<Player, Entity> = HashMap()
    }
}
