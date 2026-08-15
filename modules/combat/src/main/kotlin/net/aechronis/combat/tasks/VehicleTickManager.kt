package net.aechronis.combat.tasks

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Car
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.withCombatAttribution
import net.aechronis.watchdog.Watchdog
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.Damage
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.particle.Particle
import net.minestom.server.timer.TaskSchedule
import kotlin.math.ceil

object VehicleTickManager {
    private const val IMPACT_COOLDOWN_MS = 700L
    private const val MIN_IMPACT_SPEED = 0.08
    private const val MAX_IMPACT_DAMAGE = 8F

    private data class ImpactKey(
        val vehicle: Entity,
        val player: Player,
    )

    private val previousVehiclePositions = HashMap<Entity, Pos>()
    private val lastImpacts = HashMap<ImpactKey, Long>()
    private val collisionIndex = VehicleCollisionIndex()

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

                for ((entity, vehicle) in Vehicle.entityVehicle.toList()) {
                    if (Vehicle.playerVehicleEntity.values.none { it === entity }) {
                        vehicle.onUnoccupiedTick(entity)
                    }
                }

                val vehicles = Vehicle.entityVehicle.toList()
                val activeEntities = vehicles.map { (entity, _) -> entity }.toSet()
                collisionIndex.rebuild(MinecraftServer.getConnectionManager().onlinePlayers)

                // Keep players outside every vehicle model and handle moving impacts.
                for ((entity, vehicle) in vehicles) {
                    handlePlayerCollisions(entity, vehicle, previousVehiclePositions[entity], collisionIndex)
                }
                previousVehiclePositions.keys.removeIf { it !in activeEntities }
                for ((entity, _) in vehicles) previousVehiclePositions[entity] = entity.position
                lastImpacts.keys.removeIf { it.vehicle !in activeEntities }

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
                        playerLookingAtEntity.remove(player)
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

    fun removePlayer(player: Player) {
        lastImpacts.keys.removeIf { it.player === player }
        collisionIndex.removePlayer(player)
    }

    private fun handlePlayerCollisions(
        entity: Entity,
        vehicle: Vehicle,
        previousPosition: Pos?,
        collisionIndex: VehicleCollisionIndex,
    ) {
        val instance = entity.instance ?: return
        val position = entity.position
        val movement =
            if (previousPosition == null) {
                Vec.ZERO
            } else {
                Vec(
                    position.x - previousPosition.x,
                    position.y - previousPosition.y,
                    position.z - previousPosition.z,
                )
            }
        val impactSpeed = movement.length()
        val roll = vehicle.hitboxRoll(entity)
        val now = System.currentTimeMillis()
        val broadphaseBounds = VehicleCollisionIndex.sweptBounds(vehicle, position, previousPosition, roll)

        collisionIndex.forEachCandidate(instance, broadphaseBounds) { player ->
            fun collisionAt(checkPosition: Pos) =
                vehicle.hitbox.resolveCollision(
                    checkPosition,
                    position.yaw,
                    position.pitch,
                    roll,
                    player.position,
                    player.boundingBox.relativeStart(),
                    player.boundingBox.relativeEnd(),
                )

            // Fast vehicles can move through a player's current position in one
            // tick, so sample the swept path when the final position is clear.
            var collision = collisionAt(position)
            if (collision == null && previousPosition != null) {
                val samples = ceil(impactSpeed).toInt().coerceIn(1, 32)
                for (sample in samples - 1 downTo 1) {
                    val factor = sample.toDouble() / samples
                    val samplePosition =
                        Pos(
                            previousPosition.x + movement.x * factor,
                            previousPosition.y + movement.y * factor,
                            previousPosition.z + movement.z * factor,
                            position.yaw,
                            position.pitch,
                        )
                    collision = collisionAt(samplePosition)
                    if (collision != null) break
                }
            }
            collision ?: return@forEachCandidate

            player.teleport(collision.position)
            applyImpactVelocity(player, collision.normal, movement, position)

            if (vehicle is Car || impactSpeed < MIN_IMPACT_SPEED) return@forEachCandidate
            val key = ImpactKey(entity, player)
            if (now - (lastImpacts[key] ?: 0L) < IMPACT_COOLDOWN_MS) return@forEachCandidate
            lastImpacts[key] = now

            // Speed is measured in blocks per tick. Four hearts is an absolute
            // cap, and leaving one HP prevents a vehicle from instantly killing.
            val amount =
                (impactSpeed * 8.0)
                    .toFloat()
                    .coerceIn(0.0F, MAX_IMPACT_DAMAGE)
                    .coerceAtMost((player.health - 1.0F).coerceAtLeast(0.0F))
            if (amount <= 0.0F) return@forEachCandidate

            val driver =
                Vehicle.playerVehicleEntity.entries
                    .firstOrNull { it.value === entity }
                    ?.key
            val damage =
                Damage(DamageType.CRAMMING, driver, driver, position, amount)
                    .withCombatAttribution(CombatDamageKind.VEHICLE)
            Combat.applyDamage(player, damage, now)
        }
    }

    private fun applyImpactVelocity(
        player: Player,
        collisionNormal: Vec,
        movement: Vec,
        vehiclePosition: Pos,
    ) {
        // A moving vehicle carries the target in its direction of travel; the
        // collision normal is only a fallback for stationary overlaps.
        val speed = movement.length()
        var direction = Vec(movement.x, 0.0, movement.z)
        if (direction.lengthSquared() < 1.0e-6) direction = Vec(collisionNormal.x, 0.0, collisionNormal.z)
        if (direction.lengthSquared() < 1.0e-6) {
            val away = Vec(player.position.x - vehiclePosition.x, 0.0, player.position.z - vehiclePosition.z)
            direction = if (away.lengthSquared() < 1.0e-6) Vec(0.0, 0.0, 1.0) else away
        }
        direction = direction.normalize()

        val horizontalStrength = (1.5 + speed * 4.0).coerceAtMost(12.0)
        val verticalStrength = (0.7 + speed * 1.2).coerceAtMost(2.5)
        val current = player.velocity
        val velocity =
            Vec(
                current.x * 0.2 + direction.x * horizontalStrength,
                maxOf(current.y * 0.2, verticalStrength),
                current.z * 0.2 + direction.z * horizontalStrength,
            )
        player.velocity = velocity
        Watchdog.recordKnockback(player, velocity, "vehicle")
    }
}
