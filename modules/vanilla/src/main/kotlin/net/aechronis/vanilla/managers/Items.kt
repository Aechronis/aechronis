package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.ItemListener
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.ItemEntity
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.timer.TaskSchedule
import java.time.Duration

object Items {
    private const val UNLIMITED_STACK_SIZE = Int.MAX_VALUE

    fun init() {
        val timeStart = System.currentTimeMillis()
        ItemListener.init()
        startMagnetTask()
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
        val item = ItemEntity(stack.withMaxStackSize(UNLIMITED_STACK_SIZE))
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

    private fun startMagnetTask() {
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                val config = Vanilla.config!!
                for (instance in MinecraftServer.getInstanceManager().instances) {
                    val items = instance.entities.filterIsInstance<ItemEntity>()
                    for (item in items) {
                        if (!item.isPickable) continue
                        val stack = item.itemStack

                        var closest: ItemEntity? = null
                        var closestDistance = config.dropMagnetRadius
                        for (other in items) {
                            if (other === item || !other.isPickable) continue
                            if (!other.itemStack.isSimilar(stack)) continue
                            val distance = item.position.distance(other.position)
                            if (distance < closestDistance) {
                                closestDistance = distance
                                closest = other
                            }
                        }

                        val target = closest ?: continue
                        if (closestDistance < 0.1) continue
                        val toTarget = target.position.asVec().sub(item.position.asVec())
                        item.velocity = toTarget.normalize().mul(config.dropMagnetSpeed)
                    }
                }
            }.repeat(TaskSchedule.tick(2))
            .schedule()
    }
}
