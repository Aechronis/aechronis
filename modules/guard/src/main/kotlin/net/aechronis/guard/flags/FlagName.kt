package net.aechronis.guard.flags

enum class FlagName(
    val id: String,
) {
    BLOCK_PLACE("block-place"),
    BLOCK_BREAK("block-break"),
    BLOCK_INTERACT("block-interact"),
    TELEPORT("teleport"),
    DAMAGE("damage"),
    EXPLOSION("explosion"),
    VEHICLE_SPAWN("vehicle-spawn"),
    OTHER_DAMAGE("other-damage"),
    ;

    companion object {
        fun fromId(id: String): FlagName? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
