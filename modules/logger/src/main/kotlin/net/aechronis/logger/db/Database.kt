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

    /**
     * Adds new nullable columns to the block log table for full block-state/NBT/instance
     * capture. SQLite has no `ADD COLUMN IF NOT EXISTS`, so each column is guarded individually
     * -- safe to call on every startup. Existing rows keep these columns NULL (material-key-only
     * fidelity); rollback code must handle that degraded case explicitly.
     */
    fun migrateBlockLog() {
        val table = config.tableName
        pool.connection.use { conn ->
            addColumnIfMissing(conn, table, "instance_uuid", "TEXT")
            addColumnIfMissing(conn, table, "block_old_state", "TEXT")
            addColumnIfMissing(conn, table, "block_new_state", "TEXT")
            addColumnIfMissing(conn, table, "block_old_nbt", "BLOB")
            addColumnIfMissing(conn, table, "block_new_nbt", "BLOB")
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_instance ON `$table` (instance_uuid, ts)")
            }
        }
    }

    fun createRollbackTables() {
        val operationDdl =
            """
            CREATE TABLE IF NOT EXISTS rollback_operation (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                actor_uuid TEXT NOT NULL,
                actor_name TEXT NOT NULL,
                instance_uuid TEXT NOT NULL,
                query_desc TEXT NOT NULL,
                target_ts INTEGER NOT NULL,
                status TEXT NOT NULL,
                block_change_count INTEGER NOT NULL
            )
            """.trimIndent()
        val changeDdl =
            """
            CREATE TABLE IF NOT EXISTS rollback_change (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                operation_id INTEGER NOT NULL REFERENCES rollback_operation(id),
                change_kind TEXT NOT NULL,
                x INTEGER,
                y INTEGER,
                z INTEGER,
                before_block_state TEXT,
                before_block_nbt BLOB,
                after_block_state TEXT,
                after_block_nbt BLOB
            )
            """.trimIndent()
        pool.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(operationDdl)
                stmt.execute(changeDdl)
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_rollback_actor ON rollback_operation (actor_uuid, ts)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_rollback_change_op ON rollback_change (operation_id)")
            }
        }
    }

    private fun columnExists(
        conn: java.sql.Connection,
        table: String,
        column: String,
    ): Boolean {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) {
                    if (rs.getString("name").equals(column, ignoreCase = true)) return true
                }
            }
        }
        return false
    }

    private fun addColumnIfMissing(
        conn: java.sql.Connection,
        table: String,
        column: String,
        ddlType: String,
    ) {
        if (columnExists(conn, table, column)) return
        conn.createStatement().use { stmt ->
            stmt.execute("ALTER TABLE `$table` ADD COLUMN $column $ddlType")
        }
    }

    override fun close() {
        pool.close()
    }
}
