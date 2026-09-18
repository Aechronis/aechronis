package net.aechronis.combat.utils

import net.aechronis.combat.objects.Vehicle
import net.kyori.adventure.util.RGBLike
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import kotlin.math.abs

/** Twelve bits shared with the item and client-trail shaders. */
internal data class GunMotion(
    val startLevel: Int = 0,
    val targetCode: Int = 0,
    val samplePhase: Int = 0,
) {
    init {
        require(startLevel in 0..16 && targetCode in 0..2 && samplePhase in 0..19)
    }

    val packed: Int get() = startLevel or (targetCode shl 5) or (samplePhase shl 7)
    val targetLevel: Int get() = targetCode * 8

    fun levelAt(worldAge: Long): Int {
        val elapsed = Math.floorMod(gunAnimationPhase(worldAge) % 20 - samplePhase, 20)
        return startLevel + (targetLevel - startLevel).coerceIn(-elapsed * 2, elapsed * 2)
    }

    fun resampled(worldAge: Long): GunMotion = GunMotion(levelAt(worldAge), targetCode, gunAnimationPhase(worldAge) % 20)
}

internal fun decodeGunMotion(tint: RGBLike): GunMotion? {
    val phase = tint.red() or ((tint.green() and 1) shl 8)
    val action = (tint.green() shr 1) and 7
    if (phase >= 500 || action == 2 || action == 5) return null
    val packed = (tint.green() shr 4) or (tint.blue() shl 4)
    val start = packed and 31
    val target = (packed shr 5) and 3
    val sample = packed shr 7
    val fireAim = action == GunAnimationAction.FIRE.id && sample in 20..26
    if (start > 16 || target > 2 || sample > 19 && !fireAim) return null
    return GunMotion(start, target, if (fireAim) phase % 20 else sample)
}

/** FIRE reuses its redundant movement phase to retain the interrupted ADS clock. */
internal fun decodeGunFireAimTicks(tint: RGBLike): Int? {
    if (((tint.green() shr 1) and 7) != GunAnimationAction.FIRE.id || decodeGunMotion(tint) == null) return null
    val sample = ((tint.green() shr 4) or (tint.blue() shl 4)) shr 7
    return (sample - 20).takeIf { it in 0..6 }
}

/** Samples movement only; inventory publication remains owned by GunAnimation. */
internal object GunMotionTracker {
    private data class Sample(
        var position: Pos,
        var age: Long,
        var lastMovingAge: Long,
        var desired: Int = 0,
        var motion: GunMotion = GunMotion(),
        var transitionAge: Long = age,
    )

    private val samples = HashMap<Player, Sample>()

    fun sample(
        player: Player,
        age: Long,
        suppress: Boolean = false,
        freeze: Boolean = false,
    ): GunMotion {
        val sample = samples.getOrPut(player) { Sample(player.position, age, age - 3) }
        val elapsed = age - sample.age
        if (elapsed != 0L) {
            val dx = player.position.x - sample.position.x
            val dz = player.position.z - sample.position.z
            val distanceSquared = dx * dx + dz * dz
            val discontinuity = elapsed !in 1..5 || distanceSquared > 2.25 * elapsed * elapsed
            sample.desired =
                when {
                    discontinuity || player.vehicle != null || Vehicle.isVehicleOccupant(player) -> 0
                    distanceSquared > 0.00001 -> {
                        sample.lastMovingAge = age
                        if (player.isSprinting) 2 else 1
                    }
                    age - sample.lastMovingAge <= 2 -> sample.desired
                    else -> 0
                }
            if (discontinuity && !freeze) {
                sample.motion = GunMotion()
                sample.transitionAge = age
                sample.lastMovingAge = age - 3
            }
            sample.position = player.position
            sample.age = age
        }
        if (suppress) {
            sample.motion = GunMotion()
            sample.transitionAge = age
        } else if (!freeze) {
            val old = sample.motion
            // Settle once, before the modulo-20 clock can replay a transition.
            val level =
                if (age - sample.transitionAge >=
                    (abs(old.targetLevel - old.startLevel) + 1) / 2
                ) {
                    old.targetLevel
                } else {
                    old.levelAt(age)
                }
            sample.motion =
                if (sample.desired != old.targetCode) {
                    sample.transitionAge = age
                    GunMotion(level, sample.desired, gunAnimationPhase(age) % 20)
                } else if (level == old.targetLevel && old.startLevel != old.targetLevel) {
                    old.copy(startLevel = old.targetLevel)
                } else {
                    old
                }
        }
        return sample.motion
    }

    fun fireSample(
        player: Player,
        age: Long,
    ): GunMotion {
        val sampled = sample(player, age).resampled(age)
        // FIRE freezes this payload for both the viewmodel and its attached tracer.
        // Drop the lowered sprint pose immediately, retaining ordinary walking bob.
        val motion = sampled.copy(startLevel = sampled.startLevel.coerceAtMost(8), targetCode = sampled.targetCode.coerceAtMost(1))
        samples.getValue(player).apply {
            this.motion = motion
            transitionAge = age
        }
        return motion
    }

    fun clear(player: Player) {
        samples.remove(player)
    }
}
