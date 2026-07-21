package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import kotlin.math.floor

open class Ship(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    model: String = "${Tags.NAMESPACE}:$name",
    scale: Double,
    hitbox: Hitbox,
    health: Float = 100F,
    placeTime: Long = 1000,
    maxSpeed: Float = 0.4f,
    acceleration: Float = 0.02f,
    braking: Float = 0.04f,
    friction: Float = 0.98f,
    turnSpeed: Float = 4.0f,
    maxClimbHeight: Float = 0.5f,
    seatOffsets: List<Vec> = listOf(Vec.ZERO),
) : Car(
        name,
        itemName,
        itemLore,
        itemModel,
        model,
        scale,
        hitbox,
        health,
        placeTime,
        maxSpeed,
        acceleration,
        braking,
        friction,
        turnSpeed,
        maxClimbHeight,
        seatOffsets,
    ) {
    override fun spawn(
        player: Player,
        pos: Pos,
    ): Entity {
        val entity = super.spawn(player, pos)
        val instance = entity.instance ?: return entity
        val surfaceY = findWaterSurfaceY(instance, entity.position.x, entity.position.z, getCurrentSurfaceY(entity.position))

        if (surfaceY != null && hasWaterFootprint(instance, entity.position, getCurrentSurfaceY(entity.position))) {
            entity.teleport(entity.position.withY(getVehicleY(surfaceY)))
        }

        return entity
    }

    override fun canStartMoving(
        instance: Instance,
        position: Pos,
    ): Boolean = isHitboxInWater(instance, position)

    override fun canPlaceAt(
        instance: Instance,
        pos: Pos,
    ): Boolean {
        val waterBlockY = floor(pos.y - 1.0).toInt()
        return footprintSamplePoints(pos).all { (x, z) ->
            instance.getBlock(floor(x).toInt(), waterBlockY, floor(z).toInt()).compare(Block.WATER)
        }
    }

    override fun findSurfaceY(
        instance: Instance,
        position: Pos,
        currentSurfaceY: Double,
    ): Double? {
        val surfaceY = findWaterSurfaceY(instance, position.x, position.z, currentSurfaceY) ?: return null
        val floatedPosition = position.withY(getVehicleY(surfaceY))
        return if (hasWaterFootprint(instance, floatedPosition, surfaceY)) surfaceY else null
    }

    override fun getCurrentSurfaceY(position: Pos): Double = position.y + hitbox.getCenterOffset().y

    override fun getVehicleY(surfaceY: Double): Double = surfaceY - hitbox.getCenterOffset().y

    private fun isHitboxInWater(
        instance: Instance,
        position: Pos,
    ): Boolean {
        val currentSurfaceY = getCurrentSurfaceY(position)
        val waterSurfaceY = findWaterSurfaceY(instance, position.x, position.z, currentSurfaceY) ?: return false
        val bottomY = position.y + hitbox.getBottomOffset()
        return waterSurfaceY > bottomY && hasWaterFootprint(instance, position, currentSurfaceY)
    }

    private fun hasWaterFootprint(
        instance: Instance,
        position: Pos,
        currentSurfaceY: Double,
    ): Boolean =
        footprintSamplePoints(position).all { (x, z) ->
            findWaterSurfaceY(instance, x, z, currentSurfaceY) != null
        }

    private fun footprintSamplePoints(position: Pos): List<Pair<Double, Double>> =
        hitbox
            .getWorldCorners(position, position.yaw, position.pitch, 0f)
            .flatten()
            .map { point -> point.x to point.z }
            .plus(position.x to position.z)
            .distinct()

    private fun findWaterSurfaceY(
        instance: Instance,
        x: Double,
        z: Double,
        currentSurfaceY: Double,
    ): Double? {
        val startY = (currentSurfaceY + maxClimbHeight + 1).toInt()
        val endY = (currentSurfaceY - 10).toInt()

        for (y in startY downTo endY) {
            val block = instance.getBlock(floor(x).toInt(), y, floor(z).toInt())
            if (block.compare(Block.WATER)) return (y + 1).toDouble()
        }

        return null
    }
}
