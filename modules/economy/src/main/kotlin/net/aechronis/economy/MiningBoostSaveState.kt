package net.aechronis.economy

/** Immutable global boosts captured together for Economy persistence. */
data class MiningBoostSaveState(
    val haste: BoostSaveState?,
    val boost: BoostSaveState?,
) {
    data class BoostSaveState(
        val multiplier: Int,
        val startedAt: Long,
        val expiresAt: Long,
    )
}
