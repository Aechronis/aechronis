package net.aechronis.nodes.colonization

import net.minestom.server.ServerFlag
import net.minestom.server.collision.CollisionUtils
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.pathfinding.followers.NodeFollower
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

internal class PlayerLikeGroundFollower(
    private val entity: Entity,
) : NodeFollower {
    var lookTarget: Point? = null

    override fun moveTowards(
        target: Point,
        speed: Double,
        lookAt: Point,
    ) {
        val position = entity.position
        val movement = playerLikeGroundMovement(position, target, speed)
        val view = limitedView(position, lookAt)
        val physicsResult = CollisionUtils.handlePhysics(entity, movement)

        entity.refreshPosition(physicsResult.newPosition().withView(view.yaw(), view.pitch()))
    }

    fun lookTowards(lookAt: Point) {
        val position = entity.position
        val view = limitedView(position, lookAt)
        entity.refreshPosition(position.withView(view.yaw(), view.pitch()))
    }

    private fun limitedView(
        position: Pos,
        fallback: Point,
    ): Pos = playerLikeLimitedView(
        position,
        lookTarget ?: fallback,
        MAX_YAW_CHANGE_PER_TICK,
        MAX_PITCH_CHANGE_PER_TICK,
    )

    override fun jump(
        point: Point?,
        target: Point?,
    ) {
        if (!entity.isOnGround) return

        val jumpStrength = (entity as? LivingEntity)
            ?.getAttribute(Attribute.JUMP_STRENGTH)
            ?.value
            ?: Attribute.JUMP_STRENGTH.defaultValue()
        entity.velocity = playerLikeJumpVelocity(entity.velocity, jumpStrength)
    }

    override fun isAtPoint(point: Point): Boolean = playerLikeReachedPathPoint(entity.position, point)

    override fun movementSpeed(): Double = (entity as? LivingEntity)
        ?.getAttribute(Attribute.MOVEMENT_SPEED)
        ?.value
        ?: DEFAULT_GROUND_MOVEMENT_SPEED
}

internal fun playerLikeGroundMovement(
    position: Point,
    target: Point,
    speed: Double,
): Vec {
    val dx = target.x() - position.x()
    val dz = target.z() - position.z()
    val distance = hypot(dx, dz)
    if (distance == 0.0 || speed <= 0.0) return Vec.ZERO

    val scale = min(speed, distance) / distance
    return Vec(dx * scale, 0.0, dz * scale)
}

internal fun playerLikeGroundView(
    position: Pos,
    lookAt: Point,
): Pos {
    val dx = lookAt.x() - position.x()
    val dy = lookAt.y() - position.y()
    val dz = lookAt.z() - position.z()
    val horizontalDistance = hypot(dx, dz)
    if (horizontalDistance == 0.0) {
        return when {
            dy > 0.0 -> position.withPitch(-90F)
            dy < 0.0 -> position.withPitch(90F)
            else -> position
        }
    }

    val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
    val pitch = Math.toDegrees(atan2(-dy, horizontalDistance)).toFloat()
    return position.withView(yaw, pitch)
}

internal fun playerLikeLimitedView(
    position: Pos,
    lookAt: Point,
    maximumYawChange: Float,
    maximumPitchChange: Float,
): Pos {
    require(maximumYawChange >= 0F && maximumPitchChange >= 0F)
    val desired = playerLikeGroundView(position, lookAt)
    val yawChange = wrappedAngleDifference(position.yaw, desired.yaw)
        .coerceIn(-maximumYawChange, maximumYawChange)
    val pitchChange = (desired.pitch - position.pitch)
        .coerceIn(-maximumPitchChange, maximumPitchChange)
    return position.withView(position.yaw + yawChange, position.pitch + pitchChange)
}

internal fun playerLikeAimAligned(
    position: Pos,
    lookAt: Point,
    yawTolerance: Float,
    pitchTolerance: Float,
): Boolean {
    require(yawTolerance >= 0F && pitchTolerance >= 0F)
    val desired = playerLikeGroundView(position, lookAt)
    return abs(wrappedAngleDifference(position.yaw, desired.yaw)) <= yawTolerance &&
        abs(position.pitch - desired.pitch) <= pitchTolerance
}

private fun wrappedAngleDifference(current: Float, desired: Float): Float {
    var difference = (desired - current) % 360F
    if (difference > 180F) difference -= 360F
    if (difference < -180F) difference += 360F
    return difference
}

internal fun playerLikeJumpVelocity(
    currentVelocity: Vec,
    jumpStrength: Double,
): Vec = currentVelocity.withY(
    maxOf(jumpStrength * ServerFlag.SERVER_TICKS_PER_SECOND, MINIMUM_PATH_JUMP_VELOCITY),
)

internal fun playerLikeReachedPathPoint(
    position: Point,
    point: Point,
): Boolean {
    val deltaX = point.x() - position.x()
    val deltaY = point.y() - position.y()
    val deltaZ = point.z() - position.z()
    return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= PATH_POINT_TOLERANCE * PATH_POINT_TOLERANCE
}

private const val DEFAULT_GROUND_MOVEMENT_SPEED = 0.1
private const val MAX_YAW_CHANGE_PER_TICK = 18F
private const val MAX_PITCH_CHANGE_PER_TICK = 12F
private const val PATH_POINT_TOLERANCE = 0.3
private const val MINIMUM_PATH_JUMP_VELOCITY = 10.0
