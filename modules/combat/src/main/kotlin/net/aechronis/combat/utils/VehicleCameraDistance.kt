package net.aechronis.combat.utils

import net.aechronis.combat.objects.Hitbox
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import kotlin.math.max

private const val CAMERA_FRAMING_MULTIPLIER = 1.75
private const val CAMERA_CLEARANCE = 0.5

internal fun calculateVehicleCameraDistance(
    hitbox: Hitbox,
    cameraOrigin: Vec,
    playerScale: Double,
): Double {
    val safeScale = max(playerScale, Attribute.SCALE.minValue())
    val framedDistance = hitbox.getMaxDistanceFrom(cameraOrigin) * CAMERA_FRAMING_MULTIPLIER + CAMERA_CLEARANCE
    return max(Attribute.CAMERA_DISTANCE.defaultValue(), framedDistance / safeScale)
        .coerceIn(Attribute.CAMERA_DISTANCE.minValue(), Attribute.CAMERA_DISTANCE.maxValue())
}

internal object VehicleCameraDistance {
    private val originalDistance = HashMap<Player, Double>()

    fun apply(
        player: Player,
        hitbox: Hitbox,
        seatOffset: Vec,
    ) {
        val cameraDistance = player.getAttribute(Attribute.CAMERA_DISTANCE)
        val original = originalDistance.getOrPut(player) { cameraDistance.baseValue }
        val cameraOrigin = seatOffset.add(0.0, player.eyeHeight, 0.0)
        val playerScale = player.getAttribute(Attribute.SCALE).value
        val requiredDistance = calculateVehicleCameraDistance(hitbox, cameraOrigin, playerScale)

        cameraDistance.baseValue = max(original, requiredDistance)
    }

    fun restore(player: Player) {
        val original = originalDistance.remove(player) ?: return
        player.getAttribute(Attribute.CAMERA_DISTANCE).baseValue = original
    }
}
