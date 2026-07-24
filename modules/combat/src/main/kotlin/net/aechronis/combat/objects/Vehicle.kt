package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
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
import kotlin.math.sin

open class Vehicle(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    val model: String = "${Tags.NAMESPACE}:$name",
    val scale: Double,
    val hitbox: Hitbox,
    val health: Float = 20F,
    val placeTime: Long = 3000,
    val seatOffsets: List<Vec> = listOf(Vec.ZERO),
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
        if (!canPlaceAt(instance, pos)) return false

        // create task
        runPlaceTask(player, pos)

        return true
    }

    protected open fun canPlaceAt(
        instance: Instance,
        pos: Pos,
    ): Boolean = true

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

    open fun spawn(
        player: Player,
        pos: Pos,
    ): Entity {
        val entity = Entity(EntityType.ITEM_DISPLAY)

        // offset y so the bottom of the hitbox sits on the ground
        val adjustedPos = pos.add(0.0, hitbox.getGroundOffset(), 0.0)
        entity.setInstance(player.instance, adjustedPos)

        val meta = entity.entityMeta as ItemDisplayMeta
        meta.itemStack = ItemStack.of(Material.BONE).withItemModel(model)
        meta.posRotInterpolationDuration = 3
        meta.scale = Vec(scale)
        meta.isHasNoGravity = true

        entity.spawn()

        entityVehicle[entity] = this
        entityHealth[entity] = health

        return entity
    }

    // called when a player enters this vehicle
    open fun onEnter(
        player: Player,
        entity: Entity,
    ) {
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

        playerVehicle[player] = this
        playerVehicleEntity[player] = entity
        playerSeatEntity[player] = seatEntity
    }

    // called when a player exits this vehicle
    open fun onExit(player: Player) {
        val seatEntity = playerSeatEntity.remove(player)
        if (seatEntity != null) {
            seatEntity.removePassenger(player)
            seatEntity.remove()
        }
        playerVehicle.remove(player)
        playerVehicleEntity.remove(player)
    }

    // called every tick
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

        // update passenger seat positions
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

    // called when a player enters as a passenger
    open fun onPassengerEnter(
        player: Player,
        entity: Entity,
    ) {
        if (playerVehicle[player] != null || passengerVehicle[player] != null) return

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

        passengers.add(player)
        passengerVehicle[player] = this
        passengerVehicleEntity[player] = entity
        passengerSeatEntity[player] = seatEntity
    }

    // called when a passenger exits vehicle
    open fun onPassengerExit(player: Player) {
        passengerVehicleEntity.remove(player)?.let { entity ->
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

    // called when the vehicle takes damage
    open fun takeDamage(
        entity: Entity,
        amount: Float,
        attacker: Player?,
        weapon: Component? = null,
    ): Boolean {
        val currentHealth = entityHealth[entity] ?: return false
        val newHealth = currentHealth - amount
        entityHealth[entity] = newHealth

        if (newHealth <= 0) {
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

        // remove the displayentity
        entity.remove()
    }

    companion object {
        var playerVehicle: HashMap<Player, Vehicle> = HashMap()
        var playerVehicleEntity: HashMap<Player, Entity> = HashMap()
        var entityVehicle: HashMap<Entity, Vehicle> = HashMap()
        var entityHealth: HashMap<Entity, Float> = HashMap()

        // driver seat
        val playerSeatEntity: HashMap<Player, Entity> = HashMap()

        // passenger tracking
        val entityPassengers: HashMap<Entity, MutableList<Player>> = HashMap()
        val passengerVehicle: HashMap<Player, Vehicle> = HashMap()
        val passengerVehicleEntity: HashMap<Player, Entity> = HashMap()
        val passengerSeatEntity: HashMap<Player, Entity> = HashMap()
    }
}
