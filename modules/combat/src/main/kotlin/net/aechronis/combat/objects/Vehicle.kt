package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.constants.Tags
import net.aechronis.combat.utils.Message
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.title.Title
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.timer.TaskSchedule

open class Vehicle(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.namespace}:$name",
    val model: String = "${Tags.namespace}:$name",
    val health: Float = 20F,
    val gravity: Boolean = false,
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
    ) {
        val armorStand = LivingEntity(EntityType.ARMOR_STAND)

        armorStand.setInstance(player.instance, pos)

        armorStand.helmet = ItemStack.of(Material.BONE).withItemModel(model)

        armorStand.health = health

        val meta = armorStand.getEntityMeta()
        meta.isInvisible = true
        meta.isHasNoGravity = !gravity

        armorStand.spawn()

        playerVehicle[player] = this
    }

    open fun onTick(player: Player) { }

    companion object {
        var playerVehicle: HashMap<Player, Vehicle> = HashMap()
    }
}
