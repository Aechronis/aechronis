package net.aechronis.watchdog.checks

import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.objects.AttackFrame
import net.aechronis.watchdog.objects.FlagType
import net.aechronis.watchdog.objects.PlayerState
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
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
        val recentYawCount =
            state.rotations
                .takeLast(5)
                .map { it.yaw }
                .distinct()
                .size
        if (state.rotations.size >= 5 && recentYawCount == 1) {
            flag(attacker, FlagType.AIM, 0.25, "attack was accompanied by an unchanged view angle")
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
