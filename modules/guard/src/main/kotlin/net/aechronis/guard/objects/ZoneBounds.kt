package net.aechronis.guard.objects

data class ZoneBounds(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
) {
    init {
        require(minX <= maxX && minY <= maxY && minZ <= maxZ) { "Zone minimums must not exceed maximums" }
    }

    fun contains(
        x: Int,
        y: Int,
        z: Int,
    ): Boolean = x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    override fun toString(): String = "[$minX, $minY, $minZ] to [$maxX, $maxY, $maxZ]"
}
