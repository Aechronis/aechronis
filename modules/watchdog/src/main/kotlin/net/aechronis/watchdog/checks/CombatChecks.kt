package net.aechronis.watchdog.checks

import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.objects.AttackFrame
import net.aechronis.watchdog.objects.FlagType
import net.aechronis.watchdog.objects.PlayerState
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal object CombatChecks {
    fun onAttack(
        attacker: Player,
        attack: AttackFrame,
        state: PlayerState,
        config: WatchdogConfig,
        flag: FlagSink,
    ) {
        if (!attack.confirmed) return
        val eye = attack.attackerPosition.add(0.0, attacker.eyeHeight, 0.0)
        val minX = attack.targetPosition.x - attack.targetWidth / 2.0
        val maxX = attack.targetPosition.x + attack.targetWidth / 2.0
        val minY = attack.targetPosition.y
        val maxY = attack.targetPosition.y + attack.targetHeight
        val minZ = attack.targetPosition.z - attack.targetWidth / 2.0
        val maxZ = attack.targetPosition.z + attack.targetWidth / 2.0
        val closestX = eye.x.coerceIn(minX, maxX)
        val closestY = eye.y.coerceIn(minY, maxY)
        val closestZ = eye.z.coerceIn(minZ, maxZ)
        val reach = eye.distance(Pos(closestX, closestY, closestZ))
        if (reach > config.maxReach) {
            flag(attacker, FlagType.REACH, certainty(reach, config.maxReach), "attack reach was $reach blocks")
        }

        val angle = attackAngle(attack)
        if (angle > config.maxAttackAngleDegrees) {
            flag(attacker, FlagType.KILL_AURA, 0.3, "attack angle was ${angle.toInt()} degrees")
        }
        val recentRotations = state.rotations.takeLast(5)
        if (recentRotations.size >= 5 && recentRotations.map { it.yaw to it.pitch }.distinct().size == 1) {
            flag(attacker, FlagType.AIM, 0.25, "attack was accompanied by duplicate view angles")
        }
        if (state.rotations.size >= 3) {
            val rotations = state.rotations.takeLast(3)
            val previousDelta = rotations[1].yaw - rotations[0].yaw
            val currentDelta = rotations[2].yaw - rotations[1].yaw
            if (abs(currentDelta) > 320.0f && abs(previousDelta) < 30.0f && abs(rotations[2].yaw) < 360.0f) {
                flag(attacker, FlagType.AIM, 0.25, "attack was accompanied by a modulo-360 yaw snap")
            }
        }
    }

    private fun attackAngle(attack: AttackFrame): Double {
        val yaw = Math.toRadians(attack.yaw.toDouble())
        val pitch = Math.toRadians(attack.pitch.toDouble())
        val directionX = -sin(yaw) * cos(pitch)
        val directionY = -sin(pitch)
        val directionZ = cos(yaw) * cos(pitch)
        val targetX = attack.targetPosition.x - attack.attackerPosition.x
        val targetY = attack.targetPosition.y + attack.targetHeight / 2.0 - attack.attackerPosition.y
        val targetZ = attack.targetPosition.z - attack.attackerPosition.z
        val length = sqrt(targetX * targetX + targetY * targetY + targetZ * targetZ)
        if (length == 0.0) return 0.0
        val dot = (directionX * targetX + directionY * targetY + directionZ * targetZ) / length
        return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
    }

    private fun certainty(
        value: Double,
        limit: Double,
    ): Double = ((value / limit - 1.0) / 2.0).coerceIn(0.25, 1.0)
}
