package net.aechronis.combat.objects

import net.minestom.server.coordinate.Pos

/** Traverses a barrel toward the operator's view, respecting carriage-relative yaw limits. */
internal class FieldPieceAiming(
    private val traverseSpeed: Float,
    private val maxYaw: Float,
    private val minPitch: Float,
    private val maxPitch: Float,
) {
    fun step(
        origin: Pos,
        current: Pos,
        target: Pos,
    ): Pos {
        val yaw =
            if (maxYaw == 180f) {
                current.yaw + angleDifference(current.yaw, target.yaw).coerceIn(-traverseSpeed, traverseSpeed)
            } else {
                // Steering can move the current barrel beyond its limit; clamp both ends before traversing.
                val currentYaw = angleDifference(origin.yaw, current.yaw).coerceIn(-maxYaw, maxYaw)
                val targetYaw = angleDifference(origin.yaw, target.yaw).coerceIn(-maxYaw, maxYaw)
                origin.yaw + currentYaw + (targetYaw - currentYaw).coerceIn(-traverseSpeed, traverseSpeed)
            }
        val targetPitch = target.pitch.coerceIn(minPitch, maxPitch)
        val pitch = current.pitch + (targetPitch - current.pitch).coerceIn(-traverseSpeed, traverseSpeed)
        return origin.withView(yaw, pitch)
    }

    private fun angleDifference(
        current: Float,
        target: Float,
    ): Float {
        var delta = (target - current) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }
}
