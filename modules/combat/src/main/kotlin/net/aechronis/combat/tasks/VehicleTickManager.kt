package net.aechronis.combat.tasks

import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.Vehicle
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.particle.Particle
import net.minestom.server.timer.TaskSchedule

object VehicleTickManager {
    val playerLookingAtVehicle = HashMap<Player, Vehicle>()
    val playerLookingAtEntity = HashMap<Player, Entity>()

    fun start() {
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                // tick occupied vehicles
                for ((player, vehicle) in Vehicle.playerVehicle.toList()) {
                    vehicle.onTick(player)
                }

                val vehicles = Vehicle.entityVehicle.toList()

                // render hitboxes for all vehicles
                if (Hitbox.viewingHitboxes.isNotEmpty()) {
                    for ((entity, vehicle) in vehicles) {
                        val pos = entity.position
                        vehicle.hitbox.render(
                            entity.instance ?: continue,
                            pos,
                            pos.yaw,
                            pos.pitch,
                            0f,
                            Particle.FLAME,
                            0.3,
                        )
                    }
                }

                // check if players are looking at vehicles and spawn fake blocks around them
                // see modelmanager
                for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                    // skip players already in a vehicle
                    if (Vehicle.playerVehicle[player] != null) {
                        playerLookingAtVehicle.remove(player)
                        continue
                    }

                    // raycast to check if looking at a vehicle
                    val eyePos = player.position.add(0.0, player.eyeHeight, 0.0)
                    val direction = eyePos.direction()

                    var foundVehicle: Vehicle? = null
                    var foundEntity: Entity? = null
                    var distance = 0.0
                    while (distance <= 3 && foundVehicle == null) {
                        val checkPoint =
                            Vec(
                                eyePos.x + direction.x * distance,
                                eyePos.y + direction.y * distance,
                                eyePos.z + direction.z * distance,
                            )

                        for ((entity, vehicle) in vehicles) {
                            val vehiclePos = entity.position

                            val hitPart =
                                vehicle.hitbox.containsPoint(
                                    checkPoint,
                                    vehiclePos,
                                    vehiclePos.yaw,
                                    vehiclePos.pitch,
                                    0f,
                                )

                            if (hitPart != null) {
                                foundVehicle = vehicle
                                foundEntity = entity
                                break
                            }
                        }

                        distance += 1
                    }

                    if (foundVehicle != null && foundEntity != null) {
                        playerLookingAtVehicle[player] = foundVehicle
                        playerLookingAtEntity[player] = foundEntity
                    } else {
                        playerLookingAtVehicle.remove(player)
                        playerLookingAtEntity.remove(player)
                    }
                }
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }
}
