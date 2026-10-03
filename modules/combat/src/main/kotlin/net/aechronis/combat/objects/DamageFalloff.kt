package net.aechronis.combat.objects

/**
 * Linear damage falloff over distance: full damage up to [start] blocks, scaling
 * down to [minMultiplier] of the damage at [end] blocks and beyond.
 */
data class DamageFalloff(
    val start: Double,
    val end: Double,
    val minMultiplier: Float,
) {
    init {
        require(start.isFinite() && start >= 0.0) { "Falloff start must be a non-negative finite number" }
        require(end.isFinite() && end > start) { "Falloff end must be finite and beyond its start" }
        require(minMultiplier in 0F..1F) { "Falloff minMultiplier must be 0–1" }
    }

    fun multiplier(distance: Double): Float =
        when {
            distance <= start -> 1F
            distance >= end -> minMultiplier
            else -> (1.0 - (distance - start) / (end - start) * (1.0 - minMultiplier)).toFloat()
        }
}
