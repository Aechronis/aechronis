package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import kotlin.math.floor

open class Boat(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    model: String = "${Tags.NAMESPACE}:$name",
    scale: Double,
    hitbox: Hitbox,
    health: Health,
    placeTime: Long = 1000,
    maxSpeed: Float = 0.4f,
    acceleration: Float = 0.02f,
    braking: Float = 0.04f,
    friction: Float = 0.98f,
    turnSpeed: Float = 4.0f,
    maxClimbHeight: Float = 0.5f,
    seatOffsets: List<Vec> = listOf(Vec.ZERO),
    invisibleWhileRiding: Boolean = true,
    invulnerableWhileRiding: Boolean = true,
    val floatHeight: Double = 0.5,
    animatedParts: List<AnimatedPart> = emptyList(),
    collisionHitbox: ShulkerHitbox = ShulkerHitbox.fromHitbox(hitbox),
    val waterlineOffset: Double? = null,
    val waterHitbox: Hitbox = hitbox,
    modelScale: Vec = Vec(scale),
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
        invisibleWhileRiding,
        invulnerableWhileRiding,
        animatedParts,
        collisionHitbox,
        modelScale,
    ) {
    private val solidHitboxes = hitbox.parts.map { Hitbox(listOf(it)) }

    init {
        require(floatHeight in 0.0..1.0) { "floatHeight must be between 0.0 and 1.0" }
        require(waterlineOffset == null || waterlineOffset.isFinite()) { "Boat waterlineOffset must be finite" }
        require(waterHitbox.parts.isNotEmpty()) { "Boat waterHitbox must contain a hull" }
    }

    override fun spawn(
        instance: Instance,
        pos: Pos,
    ): Entity {
        var placement = pos
        val groundedPosition = pos.add(0.0, hitbox.getGroundOffset(), 0.0)
        val surfaceY = findWaterSurfaceY(instance, pos.x, pos.z, getCurrentSurfaceY(groundedPosition))
        if (surfaceY != null) {
            val floatedPosition = groundedPosition.withY(getVehicleY(surfaceY))
            if (hasWaterFootprint(instance, floatedPosition, surfaceY)) {
                // Vehicle.spawn adds the ground offset. Resolve flotation first so
                // the body, animated parts and collision helpers spawn together.
                placement = floatedPosition.add(0.0, -hitbox.getGroundOffset(), 0.0)
            }
        }
        return super.spawn(instance, placement)
    }

    override fun canStartMoving(
        instance: Instance,
        position: Pos,
    ): Boolean = isHitboxInWater(instance, position)

    override fun canMoveTo(
        instance: Instance,
        entity: Entity,
        position: Pos,
    ): Boolean = hasSolidClearance(instance, position) && hasVehicleClearance(instance, position, entity)

    override fun canPlaceAt(
        instance: Instance,
        pos: Pos,
    ): Boolean {
        val floatedPosition = pos.withY(getVehicleY(pos.y))
        val waterBlockY = floor(pos.y - 1.0).toInt()
        return footprintSamplePoints(floatedPosition).all { (x, z) ->
            loadedBlock(instance, floor(x).toInt(), waterBlockY, floor(z).toInt())?.compare(Block.WATER) == true
        } &&
            hasSolidClearance(instance, floatedPosition) &&
            hasVehicleClearance(instance, floatedPosition)
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

    override fun getCurrentSurfaceY(position: Pos): Double = position.y + resolvedWaterlineOffset()

    override fun getVehicleY(surfaceY: Double): Double = surfaceY - resolvedWaterlineOffset()

    private fun resolvedWaterlineOffset(): Double {
        waterlineOffset?.let { return it }
        val bottomOffset = waterHitbox.getBottomOffset()
        val topOffset = waterHitbox.getTopOffset()
        return topOffset - (topOffset - bottomOffset) * floatHeight
    }

    private fun isHitboxInWater(
        instance: Instance,
        position: Pos,
    ): Boolean {
        val currentSurfaceY = getCurrentSurfaceY(position)
        val waterSurfaceY = findWaterSurfaceY(instance, position.x, position.z, currentSurfaceY) ?: return false
        val bottomY = position.y + waterHitbox.getBottomOffset()
        return waterSurfaceY >= bottomY && hasWaterFootprint(instance, position, currentSurfaceY)
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
        waterHitbox
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
        val dimension = instance.cachedDimensionType
        val startY = floor(currentSurfaceY + maxClimbHeight + 1).toInt().coerceAtMost(dimension.maxY() - 1)
        val endY = floor(currentSurfaceY - 10).toInt().coerceAtLeast(dimension.minY())

        for (y in startY downTo endY) {
            val block = loadedBlock(instance, floor(x).toInt(), y, floor(z).toInt()) ?: return null
            if (block.compare(Block.WATER)) return (y + 1).toDouble()
            if (block.isSolid) return null
        }

        return null
    }

    private fun hasVehicleClearance(
        instance: Instance,
        position: Pos,
        excludedEntity: Entity? = null,
    ): Boolean =
        VehicleRegistry.all().none { runtime ->
            val other = runtime.entity
            other !== excludedEntity &&
                other.instance === instance &&
                hitbox.intersects(
                    runtime.vehicle.hitbox,
                    position,
                    position.yaw,
                    position.pitch,
                    0f,
                    other.position,
                    other.position.yaw,
                    other.position.pitch,
                    runtime.vehicle.hitboxRoll(other),
                )
        }

    /** Check the whole hull and superstructure, including obstacles between the corners. */
    private fun hasSolidClearance(
        instance: Instance,
        position: Pos,
    ): Boolean {
        val dimension = instance.cachedDimensionType
        val corners = hitbox.getWorldCorners(position, position.yaw, position.pitch, 0f)
        for ((index, partCorners) in corners.withIndex()) {
            val minY = partCorners.minOf { it.y }
            val maxY = partCorners.maxOf { it.y }
            if (minY < dimension.minY() || maxY > dimension.maxY()) return false
            val minX = floor(partCorners.minOf { it.x } + CLEARANCE_EPSILON).toInt()
            val maxX = floor(partCorners.maxOf { it.x } - CLEARANCE_EPSILON).toInt()
            val minZ = floor(partCorners.minOf { it.z } + CLEARANCE_EPSILON).toInt()
            val maxZ = floor(partCorners.maxOf { it.z } - CLEARANCE_EPSILON).toInt()
            for (x in minX..maxX) {
                for (z in minZ..maxZ) {
                    for (y in floor(minY + CLEARANCE_EPSILON).toInt()..floor(maxY - CLEARANCE_EPSILON).toInt()) {
                        val block = loadedBlock(instance, x, y, z) ?: return false
                        if (block.isSolid &&
                            solidHitboxes[index].intersects(
                                BLOCK_HITBOX,
                                position,
                                position.yaw,
                                position.pitch,
                                0f,
                                Pos(x + 0.5, y + 0.5, z + 0.5),
                                0f,
                                0f,
                                0f,
                            )
                        ) {
                            return false
                        }
                    }
                }
            }
        }
        return true
    }

    private fun loadedBlock(
        instance: Instance,
        x: Int,
        y: Int,
        z: Int,
    ): Block? {
        val dimension = instance.cachedDimensionType
        if (y < dimension.minY() || y >= dimension.maxY()) return null
        val chunk = instance.getChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16)) ?: return null
        chunk.lockReadLock()
        return try {
            if (chunk.isLoaded) chunk.getBlock(x, y, z) else null
        } finally {
            chunk.unlockReadLock()
        }
    }

    companion object {
        private const val CLEARANCE_EPSILON = 1.0e-7
        private val BLOCK_HITBOX = Hitbox(listOf(HitboxPart(Vec.ZERO, Vec(0.5))))
    }
}
