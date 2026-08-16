package net.aechronis.combat.utils

import net.minestom.server.coordinate.Vec
import kotlin.math.cos
import kotlin.math.sin

fun setRoll(angleRadians: Float): FloatArray {
    val half = angleRadians * 0.5f
    return floatArrayOf(0f, 0f, sin(half), cos(half))
}

// rotates a point by yaw, pitch, and roll
fun rotatePoint(
    point: Vec,
    yaw: Float,
    pitch: Float,
    roll: Float,
): Vec {
    // convert to radians
    val yawRad = Math.toRadians(-yaw.toDouble())
    val pitchRad = Math.toRadians(pitch.toDouble())
    val rollRad = Math.toRadians(roll.toDouble())

    // apply roll (around Z axis in local space which is forward)
    var x = point.x
    var y = point.y
    var z = point.z

    val cosRoll = cos(rollRad)
    val sinRoll = sin(rollRad)
    val x1 = x * cosRoll - y * sinRoll
    val y1 = x * sinRoll + y * cosRoll
    x = x1
    y = y1

    // Apply pitch (around X axis)
    val cosPitch = cos(pitchRad)
    val sinPitch = sin(pitchRad)
    val y2 = y * cosPitch - z * sinPitch
    val z2 = y * sinPitch + z * cosPitch
    y = y2
    z = z2

    // Apply yaw (around Y axis)
    val cosYaw = cos(yawRad)
    val sinYaw = sin(yawRad)
    val x3 = x * cosYaw + z * sinYaw
    val z3 = -x * sinYaw + z * cosYaw
    x = x3
    z = z3

    return Vec(x, y, z)
}

// for transforming world points to local space
fun rotatePointInverse(
    point: Vec,
    yaw: Float,
    pitch: Float,
    roll: Float,
): Vec {
    val yawRad = Math.toRadians(yaw.toDouble())
    val pitchRad = Math.toRadians(-pitch.toDouble())
    val rollRad = Math.toRadians(-roll.toDouble())

    return rotatePointInverse(
        point,
        cos(yawRad),
        sin(yawRad),
        cos(pitchRad),
        sin(pitchRad),
        cos(rollRad),
        sin(rollRad),
    )
}

internal class InverseRotation(
    yaw: Float,
    pitch: Float,
    roll: Float,
) {
    private val cosYaw: Double
    private val sinYaw: Double
    private val cosPitch: Double
    private val sinPitch: Double
    private val cosRoll: Double
    private val sinRoll: Double

    init {
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(-pitch.toDouble())
        val rollRad = Math.toRadians(-roll.toDouble())
        cosYaw = cos(yawRad)
        sinYaw = sin(yawRad)
        cosPitch = cos(pitchRad)
        sinPitch = sin(pitchRad)
        cosRoll = cos(rollRad)
        sinRoll = sin(rollRad)
    }

    fun apply(point: Vec): Vec =
        rotatePointInverse(
            point,
            cosYaw,
            sinYaw,
            cosPitch,
            sinPitch,
            cosRoll,
            sinRoll,
        )
}

private fun rotatePointInverse(
    point: Vec,
    cosYaw: Double,
    sinYaw: Double,
    cosPitch: Double,
    sinPitch: Double,
    cosRoll: Double,
    sinRoll: Double,
): Vec {
    var x = point.x
    var y = point.y
    var z = point.z

    // yaw
    val x1 = x * cosYaw + z * sinYaw
    val z1 = -x * sinYaw + z * cosYaw
    x = x1
    z = z1

    // pitch
    val y2 = y * cosPitch - z * sinPitch
    val z2 = y * sinPitch + z * cosPitch
    y = y2
    z = z2

    // roll
    val x3 = x * cosRoll - y * sinRoll
    val y3 = x * sinRoll + y * cosRoll
    x = x3
    y = y3

    return Vec(x, y, z)
}
