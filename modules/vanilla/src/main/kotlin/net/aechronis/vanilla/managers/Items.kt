package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.ItemListener
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.ItemEntity
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.timer.TaskSchedule
import java.time.Duration

object Items {
    fun init() {
        val timeStart = System.currentTimeMillis()
        ItemListener.init()
        val timeEnd = System.currentTimeMillis()
        println("Items enabled in ${timeEnd - timeStart}ms")
    }

    fun spawn(
        instance: Instance,
        position: Pos,
        stack: ItemStack,
        velocity: Vec = Vec.ZERO,
    ): ItemEntity {
        val config = Vanilla.config!!
        val item = ItemEntity(stack)
        item.isPickable = false
        item.setInstance(instance, position)
        item.velocity = velocity
        item
            .scheduler()
            .buildTask { item.isPickable = true }
            .delay(TaskSchedule.millis(config.dropPickupDelayMs))
            .schedule()
        item.scheduleRemove(Duration.ofSeconds(config.dropDespawnSeconds))
        return item
    }
}
