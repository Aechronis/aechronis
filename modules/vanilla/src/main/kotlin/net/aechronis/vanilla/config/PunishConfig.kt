package net.aechronis.vanilla.config

/** Settings which identify the current server iteration and punishment database. */
data class PunishConfig(
    val databasePath: String = "punishments/punishments",
    /** Change this at the start of every iteration to reset automatic strikes. */
    val iterationId: String = "default",
)
