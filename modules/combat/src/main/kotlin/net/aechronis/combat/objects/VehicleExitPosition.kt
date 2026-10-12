package net.aechronis.combat.objects

import net.aechronis.combat.utils.rotatePoint
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/** Physical dismount placement, independent of crew-session teardown. */
internal object VehicleExitPosition {
    /** Prefer a nearby physical deck surface before the hull-wide fallback. */
    fun move(
        player: Player,
        vehicle: Vehicle,
        entity: Entity,
        definition: VehicleSeat,
        stationPosition: () -> Pos,
    ) {
        val instance = entity.instance ?: return
        definition.exitOffset?.let { offset ->
            val position = entity.position.add(rotatePoint(offset, entity.position.yaw, entity.position.pitch, vehicle.hitboxRoll(entity)))
            if (isSafeExitPosition(player, position, instance)) {
                player.teleport(position.withView(player.position.yaw, player.position.pitch))
                return
            }
        }
        val station = stationPosition()
        val clearance = max(player.boundingBox.width(), player.boundingBox.depth()) / 2 + 0.05
        val candidates =
            vehicle.collisionHitbox
                .at(entity.position, vehicle.hitboxRoll(entity))
                .boxes
                .map { box ->
                    val centerX = (box.min.x + box.max.x) / 2
                    val centerZ = (box.min.z + box.max.z) / 2
                    // A narrow platform collapses to one shared midpoint; deriving
                    // both ends separately can invert the range through rounding.
                    val xRadius = ((box.max.x - box.min.x) / 2 - clearance).coerceAtLeast(0.0)
                    val zRadius = ((box.max.z - box.min.z) / 2 - clearance).coerceAtLeast(0.0)
                    Pos(
                        station.x.coerceIn(centerX - xRadius, centerX + xRadius),
                        box.max.y + 0.001,
                        station.z.coerceIn(centerZ - zRadius, centerZ + zRadius),
                        player.position.yaw,
                        player.position.pitch,
                    )
                }.filter { candidate ->
                    val dx = candidate.x - station.x
                    val dz = candidate.z - station.z
                    dx * dx + dz * dz <= 64.0 && abs(candidate.y - station.y) <= 6.0
                }.sortedBy { it.distanceSquared(station) }
        val safe =
            candidates.firstOrNull { candidate ->
                isSafeExitPosition(player, candidate, instance) &&
                    vehicle.hitbox.resolveCollision(
                        entity.position,
                        entity.position.yaw,
                        entity.position.pitch,
                        vehicle.hitboxRoll(entity),
                        candidate,
                        player.boundingBox.relativeStart(),
                        player.boundingBox.relativeEnd(),
                    ) == null
            }
        if (safe != null) player.teleport(safe) else moveToSafeExit(player, vehicle, entity)
    }

    private fun moveToSafeExit(
        player: Player,
        vehicle: Vehicle,
        source: Entity,
    ) {
        val instance = source.instance ?: return
        val sourcePosition = source.position
        val box = player.boundingBox
        val clearance = max(box.width(), box.depth()) + 0.35
        val baseRadius = max(vehicle.hitbox.getMaxDistanceFrom(Vec.ZERO), vehicle.collisionHitbox.radius) + clearance
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

        vehicle.collisionHitbox
            .at(sourcePosition, vehicle.hitboxRoll(source))
            .resolveCollision(
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
                    if (instance.getBlock(x, y, z).solid()) return false
                }
            }
        }
        return VehicleRegistry.all().none { runtime ->
            val entity = runtime.entity
            val vehicle = runtime.vehicle
            entity.instance === instance &&
                vehicle.collisionHitbox
                    .at(entity.position, vehicle.hitboxRoll(entity))
                    .resolveCollision(position, start, end) != null
        }
    }
}
