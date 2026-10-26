package net.aechronis.economy

import org.h2.jdbcx.JdbcConnectionPool
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/** Keeps frequent mining credits from closing and reopening the H2 file for each query. */
internal class EconomyDatabase(
    databasePath: Path,
) : AutoCloseable {
    private val url: String
    private var pool: JdbcConnectionPool? = null

    init {
        databasePath.parent?.let(Files::createDirectories)
        url = "jdbc:h2:file:${databasePath.toString().replace('\\', '/')};DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=5000"
    }

    @Synchronized
    fun connection(): Connection {
        val connections = pool ?: JdbcConnectionPool.create(url, "", "").also { pool = it }
        return connections.connection
    }

    @Synchronized
    override fun close() {
        pool?.dispose()
        pool = null
    }
}
