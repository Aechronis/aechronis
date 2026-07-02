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
                jdbcUrl = "jdbc:sqlite:${config.databasePath}"
                maximumPoolSize = config.poolSize
                poolName = "logger-pool"
                driverClassName = "org.sqlite.JDBC"
                connectionInitSql = "PRAGMA busy_timeout = 5000"
            },
        )

    val dataSource: DataSource get() = pool

    val tableName: String get() = config.tableName

    fun create() {
        val table = config.tableName
        val ddl =
            """
            CREATE TABLE IF NOT EXISTS `$table` (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                player_uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                block_old TEXT NOT NULL,
                block_new TEXT NOT NULL,
                action INTEGER NOT NULL
            )
            """.trimIndent()
        pool.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute(ddl)
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_pos ON `$table` (x, y, z, ts)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_player ON `$table` (player_uuid, ts)")
            }
        }
    }

    override fun close() {
        pool.close()
    }
}
