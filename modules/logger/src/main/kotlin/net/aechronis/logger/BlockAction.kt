package net.aechronis.logger

enum class BlockAction(
    val id: Byte,
) {
    BREAK(0),
    PLACE(1),
    INTERACT(2),
    ;

    companion object {
        fun fromId(id: Byte): BlockAction =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException()
    }
}
