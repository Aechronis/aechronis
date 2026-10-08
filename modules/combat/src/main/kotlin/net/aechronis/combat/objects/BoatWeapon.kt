package net.aechronis.combat.objects

import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec

/**
 * One traversing mount. Offsets are vehicle-local blocks, with +Z forward.
 * The component and muzzle offsets already face [neutralYaw] at rest.
 * [operatorOffset] is relative to the hull; [interactionHitbox] is relative to the pivot.
 * Multiple muzzles fire alternately, consuming one shell per projectile.
 */
data class BoatWeapon(
    val name: String,
    val model: String,
    val pivotOffset: Vec,
    val muzzleOffsets: List<Vec>,
    val operatorOffset: Vec,
    val interactionHitbox: Hitbox,
    val neutralYaw: Float = 0f,
    val maxYaw: Float = 60f,
    val traverseSpeed: Float = 2f,
    val minPitch: Float = -15f,
    val maxPitch: Float = 5f,
    val reloadTime: Long = 8000,
    val projectileSpeed: Double = 4.0,
    val projectileExplosionRadius: Int = 1,
    val projectileExplosionDamage: Float = 20f,
    val projectileMaxRange: Double = 192.0,
    /** Places the operator's eye this far behind the active muzzle, on its bore line. */
    val scopeEyeDistance: Double? = null,
) {
    init {
        require(name.isNotBlank() && model.isNotBlank())
        require(pivotOffset.finite() && muzzleOffsets.isNotEmpty() && muzzleOffsets.all { it.finite() && it.lengthSquared() > 0.0 })
        require(operatorOffset.finite())
        require(interactionHitbox.parts.isNotEmpty())
        require(interactionHitbox.parts.all { it.offset.finite() && it.size.finite() && it.size.x > 0 && it.size.y > 0 && it.size.z > 0 })
        require(neutralYaw.isFinite() && maxYaw.isFinite() && maxYaw in 0f..180f)
        require(traverseSpeed.isFinite() && traverseSpeed > 0f)
        require(minPitch.isFinite() && maxPitch.isFinite() && minPitch in -90f..90f && maxPitch in minPitch..90f)
        require(reloadTime > 0 && projectileSpeed.isFinite() && projectileSpeed > 0.0)
        require(projectileExplosionRadius >= 0 && projectileExplosionDamage.isFinite() && projectileExplosionDamage >= 0f)
        require(projectileMaxRange.isFinite() && projectileMaxRange > 0.0)
        require(scopeEyeDistance == null || (scopeEyeDistance.isFinite() && scopeEyeDistance > 0.0))
    }

    private fun Vec.finite() = x.isFinite() && y.isFinite() && z.isFinite()
}

/** A gun's moving selection shape, independent of the hull's interaction and damage geometry. */
internal data class WeaponInteractionTarget(
    val stationIndex: Int,
    val position: Pos,
    val hitbox: Hitbox,
)
