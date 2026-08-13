package net.aechronis.watchdog.checks

internal data class CollisionSnapshot(
    val loaded: Boolean,
    val supported: Boolean,
    val insideSolid: Boolean,
    val inLiquid: Boolean,
    val belowLiquid: Boolean,
    val climbable: Boolean,
) {
    val movementExempt: Boolean
        get() = supported || climbable || inLiquid || belowLiquid
}
