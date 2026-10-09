package net.aechronis.combat.objects

import net.minestom.server.coordinate.Vec

/** A crew position. Its order in the vehicle definition is its permanent hotbar key. */
data class VehicleSeat(
    val id: String,
    val name: String,
    val role: VehicleSeatRole,
    val offset: Vec = Vec.ZERO,
    val weaponId: String? = null,
    val standing: Boolean = false,
    val invisible: Boolean? = null,
    val protected: Boolean? = null,
    val exitOffset: Vec? = null,
    /** The rider keeps their own hotbar and can fire handheld guns; no seat-switching overlay. */
    val handheld: Boolean = false,
) {
    init {
        require(id.matches(Regex("[a-z0-9_-]+"))) { "Invalid crew seat ID: $id" }
        require(name.isNotBlank()) { "Crew seats need a display name" }
        require(offset.finite() && (exitOffset == null || exitOffset.finite())) { "Crew seat offsets must be finite" }
        require(role != VehicleSeatRole.GUNNER || !weaponId.isNullOrBlank()) { "A gunner seat must name its weapon" }
    }

    private fun Vec.finite() = x.isFinite() && y.isFinite() && z.isFinite()
}

enum class VehicleSeatRole(
    val drives: Boolean,
    val usesWeapon: Boolean,
    val icon: String,
) {
    DRIVER(true, false, "driver"),
    GUNNER(false, true, "gunner"),
    PILOT(true, true, "pilot"),
    OPERATOR(true, true, "operator"),
}
