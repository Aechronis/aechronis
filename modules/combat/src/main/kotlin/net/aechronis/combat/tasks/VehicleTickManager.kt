package net.aechronis.combat.tasks

import net.aechronis.combat.objects.Vehicle
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.TaskSchedule

object VehicleTickManager {
    fun start() {
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                for ((player, vehicle) in Vehicle.playerVehicle) {
                    vehicle.onTick(player)
                }
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }
}
