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

    var x = point.x
    var y = point.y
    var z = point.z

    // yaw
    val cosYaw = cos(yawRad)
    val sinYaw = sin(yawRad)
    val x1 = x * cosYaw + z * sinYaw
    val z1 = -x * sinYaw + z * cosYaw
    x = x1
    z = z1

    // pitch
    val cosPitch = cos(pitchRad)
    val sinPitch = sin(pitchRad)
    val y2 = y * cosPitch - z * sinPitch
    val z2 = y * sinPitch + z * cosPitch
    y = y2
    z = z2

    // roll
    val cosRoll = cos(rollRad)
    val sinRoll = sin(rollRad)
    val x3 = x * cosRoll - y * sinRoll
    val y3 = x * sinRoll + y * cosRoll
    x = x3
    y = y3

    return Vec(x, y, z)
}
