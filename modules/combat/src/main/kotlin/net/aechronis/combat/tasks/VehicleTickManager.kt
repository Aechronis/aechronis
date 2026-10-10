package net.aechronis.combat.tasks

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Car
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.objects.VehicleRegistry
import net.aechronis.combat.objects.VehicleRuntime
import net.aechronis.combat.objects.VehicleSeatRole
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.rotatePoint
import net.aechronis.combat.utils.rotatePointInverse
import net.aechronis.combat.utils.withCombatAttribution
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.entity.RelativeFlags
import net.minestom.server.entity.damage.Damage
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.instance.Instance
import net.minestom.server.timer.TaskSchedule
import net.minestom.server.utils.chunk.ChunkUtils
import kotlin.math.abs
import kotlin.math.ceil

object VehicleTickManager {
    private const val IMPACT_COOLDOWN_MS = 700L
    private const val MIN_IMPACT_SPEED = 0.08
    private const val MAX_IMPACT_DAMAGE = 8F
    private const val MAX_CARRY_HEIGHT = 5.0

    private data class ImpactKey(
        val vehicle: Entity,
        val player: Player,
    )

    private data class VehiclePose(
        val position: Pos,
        val roll: Float,
        val instance: Instance?,
    )

    private data class PlayerSupport(
        val entity: Entity,
        val previous: VehiclePose,
        val distance: Double,
    )

    private val previousVehiclePoses = HashMap<Entity, VehiclePose>()
    private val lastImpacts = HashMap<ImpactKey, Long>()
    private val collisionIndex = VehicleCollisionIndex()

    fun start() {
        ModuleScheduler
            .buildTask {
                Vehicle.reconcileOccupants()
                // Newly spawned vehicles need a baseline before their first movement.
                for (runtime in VehicleRegistry.all()) {
                    previousVehiclePoses.putIfAbsent(
                        runtime.entity,
                        VehiclePose(runtime.entity.position, runtime.vehicle.hitboxRoll(runtime.entity), runtime.entity.instance),
                    )
                }

                // Select the carried viewers before any hull or part sends movement.
                collisionIndex.rebuild(MinecraftServer.getConnectionManager().onlinePlayers)
                val playerSupports = findPlayerSupports(VehicleRegistry.all())

                // tick occupied vehicles
                for (ride in VehicleRegistry.rides().filter { it.role.drives }) {
                    if (ride.role.usesWeapon) ride.vehicle.updateAmmoReload(ride.player)
                    ride.vehicle.onTick(ride.player)
                }

                for (runtime in VehicleRegistry.all()) {
                    if (VehicleRegistry.driverOf(runtime.entity) == null) {
                        runtime.vehicle.onUnoccupiedTick(runtime.entity)
                    }
                }

                // Weapon operators remain active without a helmsman and follow the final hull pose.
                for (ride in VehicleRegistry.rides().filter { it.role == VehicleSeatRole.GUNNER }) {
                    ride.vehicle.updateAmmoReload(ride.player)
                    ride.vehicle.onGunnerTick(ride.player)
                }

                val vehicles = VehicleRegistry.all()
                vehicles.forEach {
                    it.updateAnimatedParts()
                    it.updateCollisionHitbox()
                }
                val vehicleLookIndex = VehicleInteractionTracker.prepareVehicleLookIndex(vehicles.map { it.entity to it.vehicle })
                val activeEntities = vehicles.map { it.entity }.toSet()
                val supports = carryStandingPlayers(vehicles, playerSupports)
                // Carrying may move a player into another collision-index cell.
                collisionIndex.rebuild(MinecraftServer.getConnectionManager().onlinePlayers)

                // Clients collide with the shulkers. Resolve overlaps/moving impacts
                // against those same cubes, independently of interaction/damage hitboxes.
                for (runtime in vehicles) {
                    val previous = previousVehiclePoses[runtime.entity]?.takeIf { it.instance === runtime.entity.instance }
                    handlePlayerCollisions(runtime.entity, runtime.vehicle, previous, collisionIndex, supports)
                }
                vehicles.forEach { it.flushCarriedMovement() }
                previousVehiclePoses.keys.removeIf { it !in activeEntities }
                for (runtime in vehicles) {
                    previousVehiclePoses[runtime.entity] =
                        VehiclePose(runtime.entity.position, runtime.vehicle.hitboxRoll(runtime.entity), runtime.entity.instance)
                }
                lastImpacts.keys.removeIf { it.vehicle !in activeEntities }

                VehicleInteractionTracker.update(MinecraftServer.getConnectionManager().onlinePlayers, vehicleLookIndex)
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }

    fun removePlayer(player: Player) {
        VehicleRegistry.all().forEach { it.removeCarriedPlayer(player) }
        VehicleInteractionTracker.removePlayer(player)
        lastImpacts.keys.removeIf { it.player === player }
        collisionIndex.removePlayer(player)
    }

    fun shutdown() {
        VehicleRegistry.all().forEach { it.setCarriedPlayers(emptySet()) }
        previousVehiclePoses.clear()
        lastImpacts.clear()
        VehicleInteractionTracker.clear()
        collisionIndex.clear()
    }

    private fun findPlayerSupports(vehicles: List<VehicleRuntime>): Map<Player, PlayerSupport> {
        val supports = HashMap<Player, PlayerSupport>()
        for (runtime in vehicles) {
            val entity = runtime.entity
            val instance = entity.instance ?: continue
            if (entity.isRemoved) continue
            val current = VehiclePose(entity.position, runtime.vehicle.hitboxRoll(entity), instance)
            val previous = previousVehiclePoses[entity]?.takeIf { it.instance === instance } ?: current
            // Check the hull before it moved, including when it travels past the player in one tick.
            val shape = runtime.vehicle.collisionHitbox.at(previous.position, previous.roll)
            val bounds = VehicleCollisionIndex.sweptBounds(listOf(shape)) ?: continue
            collisionIndex.forEachCandidate(instance, bounds.copy(maxY = bounds.maxY + MAX_CARRY_HEIGHT)) { player ->
                if (player.isRemoved || player.vehicle != null || VehicleRegistry.ride(player) != null) return@forEachCandidate
                val distance =
                    shape.supportDistance(
                        player.position,
                        player.boundingBox.relativeStart(),
                        player.boundingBox.relativeEnd(),
                        MAX_CARRY_HEIGHT,
                    ) ?: return@forEachCandidate
                // Choose one nearest support before moving anyone; overlapping hulls must not carry twice.
                if (distance < (supports[player]?.distance ?: Double.POSITIVE_INFINITY)) {
                    supports[player] = PlayerSupport(entity, previous, distance)
                }
            }
        }

        val carriedPlayers = supports.entries.groupBy({ it.value.entity }, { it.key })
        vehicles.forEach { it.setCarriedPlayers(carriedPlayers[it.entity]?.toSet().orEmpty()) }
        return supports
    }

    private fun carryStandingPlayers(
        vehicles: List<VehicleRuntime>,
        supports: Map<Player, PlayerSupport>,
    ): Map<Player, Entity> {
        val active = vehicles.associateBy { it.entity }
        val carried = HashMap<Player, Entity>()
        for ((player, support) in supports) {
            val runtime = active[support.entity] ?: continue
            val entity = runtime.entity
            if (
                entity.isRemoved ||
                player.isRemoved ||
                entity.instance !== player.instance ||
                player.vehicle != null ||
                VehicleRegistry.ride(player) != null
            ) {
                runtime.removeCarriedPlayer(player)
                continue
            }
            runtime.rememberCarryOrigin(player)
            carried[player] = entity
            val current = VehiclePose(entity.position, runtime.vehicle.hitboxRoll(entity), entity.instance)
            if (support.previous == current) continue
            val target =
                carriedPosition(
                    player.position,
                    support.previous.position,
                    support.previous.roll,
                    current.position,
                    current.roll,
                )
            val delta = target.asVec().sub(player.position)
            if (delta.lengthSquared() < 1.0e-12) continue
            if (ChunkUtils.isLoaded(player.instance, target)) {
                // Publish the player's final displacement with the hull after collision
                // resolution. Continuous carrying must not send an earlier standalone teleport.
                player.refreshPosition(target)
            } else {
                // A discontinuous move into an unloaded chunk still needs Minestom's loader.
                runtime.removeCarriedPlayer(player)
                carried.remove(player)
                player.teleport(target.withView(0f, 0f), null, RelativeFlags.VIEW, false)
            }
        }
        return carried
    }

    internal fun carriedPosition(
        playerPosition: Pos,
        previousPosition: Pos,
        previousRoll: Float,
        position: Pos,
        roll: Float,
    ): Pos {
        val local =
            rotatePointInverse(
                playerPosition.asVec().sub(previousPosition),
                previousPosition.yaw,
                previousPosition.pitch,
                previousRoll,
            )
        val carried = position.add(rotatePoint(local, position.yaw, position.pitch, roll))
        return carried.withView(playerPosition.yaw, playerPosition.pitch)
    }

    private fun handlePlayerCollisions(
        entity: Entity,
        vehicle: Vehicle,
        previousPose: VehiclePose?,
        collisionIndex: VehicleCollisionIndex,
        supports: Map<Player, Entity>,
    ) {
        val instance = entity.instance ?: return
        val position = entity.position
        val previousPosition = previousPose?.position
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
        val shapes = prepareCollisionSweep(vehicle.collisionHitbox, position, roll, previousPosition, previousPose?.roll ?: roll)
        val broadphaseBounds = VehicleCollisionIndex.sweptBounds(shapes) ?: return

        collisionIndex.forEachCandidate(instance, broadphaseBounds) { player ->
            val supported = supports[player] === entity
            // A carried player already followed this hull's sweep. Only its final shape can obstruct them.
            val collision =
                (if (supported) shapes.take(1) else shapes).firstNotNullOfOrNull {
                    it.resolveCollision(player.position, player.boundingBox.relativeStart(), player.boundingBox.relativeEnd())
                } ?: return@forEachCandidate

            if (player in supports) player.refreshPosition(collision.position) else player.teleport(collision.position)
            if (supported) return@forEachCandidate
            // The top is a walkable surface, not a side impact. Do not launch or
            // damage someone landing on a vehicle or being lifted by its deck.
            if (collision.normal.y > 0.5) return@forEachCandidate
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

            val driver = VehicleRegistry.driverOf(entity)?.player
            val damage =
                Damage(DamageType.CRAMMING, driver, driver, position, amount)
                    .withCombatAttribution(CombatDamageKind.VEHICLE)
            Combat.applyDamage(player, damage, now)
        }
    }

    internal fun prepareCollisionSweep(
        hitbox: ShulkerHitbox,
        position: Pos,
        roll: Float,
        previousPosition: Pos?,
        previousRoll: Float,
    ): List<ShulkerHitbox.Shape> {
        if (previousPosition == null) return listOf(hitbox.at(position, roll))

        fun angleDelta(
            current: Float,
            previous: Float,
        ): Float = ((current - previous + 180f) % 360f + 360f) % 360f - 180f
        val yawDelta = angleDelta(position.yaw, previousPosition.yaw)
        val pitchDelta = angleDelta(position.pitch, previousPosition.pitch)
        val rollDelta = angleDelta(roll, previousRoll)
        val travel =
            position.distance(previousPosition) +
                hitbox.radius * Math.toRadians((abs(yawDelta) + abs(pitchDelta) + abs(rollDelta)).toDouble())
        val samples = ceil(travel / 0.25).toInt().coerceIn(1, 128)
        return (samples downTo 1).map { sample ->
            if (sample == samples) {
                hitbox.at(position, roll)
            } else {
                val factor = sample.toDouble() / samples
                val pose =
                    Pos(
                        previousPosition.x + (position.x - previousPosition.x) * factor,
                        previousPosition.y + (position.y - previousPosition.y) * factor,
                        previousPosition.z + (position.z - previousPosition.z) * factor,
                        previousPosition.yaw + yawDelta * factor.toFloat(),
                        previousPosition.pitch + pitchDelta * factor.toFloat(),
                    )
                hitbox.at(pose, previousRoll + rollDelta * factor.toFloat())
            }
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
    }
}
