package net.aechronis.logger

data class LoggerConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val poolSize: Int = 4,
    val tableName: String = "block_log",
)
