package net.aechronis.logger.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.aechronis.logger.LoggerConfig
import javax.sql.DataSource

class Database(
    private val config: LoggerConfig,
) : AutoCloseable {
    private val pool: HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.username
                password = config.password
                maximumPoolSize = config.poolSize
                poolName = "logger-pool"
                driverClassName = "org.mariadb.jdbc.Driver"
            },
        )

    val dataSource: DataSource get() = pool

    val tableName: String get() = config.tableName

    fun create() {
        val ddl =
            """
            CREATE TABLE IF NOT EXISTS `${config.tableName}` (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                ts BIGINT NOT NULL,
                player_uuid CHAR(36) NOT NULL,
                player_name VARCHAR(16) NOT NULL,
                x INT NOT NULL,
                y INT NOT NULL,
                z INT NOT NULL,
                block_old VARCHAR(255) NOT NULL,
                block_new VARCHAR(255) NOT NULL,
                action TINYINT NOT NULL,
                INDEX idx_pos (x, y, z, ts),
                INDEX idx_player (player_uuid, ts)
            )
            """.trimIndent()
        pool.connection.use { conn ->
            conn.createStatement().use { it.execute(ddl) }
        }
    }

    override fun close() {
        pool.close()
    }
}
