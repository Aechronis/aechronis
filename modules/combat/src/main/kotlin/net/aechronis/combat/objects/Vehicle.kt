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
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.timer.TaskSchedule

open class Vehicle(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.namespace}:$name",
    val model: String = "${Tags.namespace}:$name",
    val hitbox: Hitbox,
    val health: Float = 20F,
    val placeTime: Long = 3000,
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

        // create task
        runPlaceTask(player, pos)

        return true
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
        meta.scale = Vec(7.0, 7.0, 7.0)
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
        entity.addPassenger(player)
        playerVehicle[player] = this
        playerVehicleEntity[player] = entity
    }

    // called when a player exits this vehicle
    open fun onExit(player: Player) {
        playerVehicleEntity[player]?.removePassenger(player)
        playerVehicle.remove(player)
        playerVehicleEntity.remove(player)
    }

    // called every tick
    open fun onTick(player: Player) {
        val inputEvent = KeyPressListener.playerInputEvent[player]
        if (inputEvent?.isHoldingShiftKey == true) {
            onExit(player)
        }
    }

    // called when the vehicle takes damage
    open fun takeDamage(
        entity: Entity,
        amount: Float,
        attacker: Player?,
    ): Boolean {
        val currentHealth = entityHealth[entity] ?: return false
        val newHealth = currentHealth - amount
        entityHealth[entity] = newHealth

        if (newHealth <= 0) {
            destroy(entity)
            return true
        }
        return false
    }

    // called when vehicle is destroyed
    open fun destroy(entity: Entity) {
        // Eject all passengers that are in this specific entity
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
    }
}
