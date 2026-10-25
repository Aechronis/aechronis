package net.aechronis.gems

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

class GemRepository(
    databasePath: Path = Path.of("gems", "gems").toAbsolutePath().normalize(),
) {
    private val url: String

    init {
        databasePath.parent?.let(Files::createDirectories)
        url = "jdbc:h2:file:${databasePath.toString().replace('\\', '/')};DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=5000"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS gems (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        player_name VARCHAR(16) NOT NULL,
                        gem_amount BIGINT NOT NULL CHECK (gem_amount >= 0)
                    )
                    """.trimIndent(),
                )
                statement.execute("CREATE INDEX IF NOT EXISTS idx_gems_name ON gems(player_name)")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS gem_transactions (
                        transaction_id VARCHAR(36) PRIMARY KEY,
                        player_uuid VARCHAR(36) NOT NULL,
                        player_name VARCHAR(16) NOT NULL,
                        product VARCHAR(64) NOT NULL,
                        amount BIGINT NOT NULL,
                        created_at BIGINT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_gem_transactions_player_created ON gem_transactions(player_uuid, created_at DESC)",
                )
            }
        }
    }

    fun rememberPlayer(
        uuid: UUID,
        name: String,
    ) {
        DriverManager.getConnection(url).use { connection ->
            connection
                .prepareStatement(
                    "UPDATE gems SET player_name = ? WHERE player_uuid = ?",
                ).use { update ->
                    update.setString(1, name)
                    update.setString(2, uuid.toString())
                    // Refreshing an existing identity must never rewrite its balance.
                    if (update.executeUpdate() == 0) {
                        try {
                            connection
                                .prepareStatement(
                                    "INSERT INTO gems (player_uuid, player_name, gem_amount) VALUES (?, ?, 0)",
                                ).use { insert ->
                                    insert.setString(1, uuid.toString())
                                    insert.setString(2, name)
                                    insert.executeUpdate()
                                }
                        } catch (exception: SQLException) {
                            if (exception.sqlState != "23505") throw exception
                            // Another connection created the account after our initial update.
                            update.executeUpdate()
                        }
                    }
                }
        }
    }

    fun playersMatching(prefix: String): List<GemPlayer> =
        DriverManager.getConnection(url).use { connection ->
            connection
                .prepareStatement(
                    "SELECT player_uuid, player_name, gem_amount FROM gems WHERE LOWER(player_name) LIKE ? ORDER BY player_name LIMIT 100",
                ).use { statement ->
                    statement.setString(1, "${prefix.lowercase()}%")
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    GemPlayer(
                                        UUID.fromString(rows.getString("player_uuid")),
                                        rows.getString("player_name"),
                                        rows.getLong("gem_amount"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    fun findPlayer(name: String): GemPlayer? =
        DriverManager.getConnection(url).use { connection ->
            connection
                .prepareStatement(
                    "SELECT player_uuid, player_name, gem_amount FROM gems WHERE LOWER(player_name) = LOWER(?)",
                ).use { statement ->
                    statement.setString(1, name)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) {
                            GemPlayer(
                                UUID.fromString(rows.getString("player_uuid")),
                                rows.getString("player_name"),
                                rows.getLong("gem_amount"),
                            )
                        } else {
                            null
                        }
                    }
                }
        }

    /** Returns the newest transactions for a player. */
    fun transactions(
        uuid: UUID,
        limit: Int = 20,
    ): List<GemTransaction> {
        require(limit in 1..100) { "Transaction limit must be between 1 and 100" }
        return DriverManager.getConnection(url).use { connection ->
            connection
                .prepareStatement(
                    "SELECT transaction_id, product, amount, created_at FROM gem_transactions WHERE player_uuid = ? ORDER BY created_at DESC, transaction_id DESC LIMIT ?",
                ).use { statement ->
                    statement.setString(1, uuid.toString())
                    statement.setInt(2, limit)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    GemTransaction(
                                        UUID.fromString(rows.getString("transaction_id")),
                                        rows.getString("product"),
                                        rows.getLong("amount"),
                                        rows.getLong("created_at"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }
    }

    fun adjust(
        uuid: UUID,
        amount: Long,
    ): Long? {
        require(amount != 0L) { "Amount must not be zero" }
        return DriverManager.getConnection(url).use { connection ->
            connection.autoCommit = false
            try {
                val changed =
                    connection
                        .prepareStatement(
                            "UPDATE gems SET gem_amount = gem_amount + ? WHERE player_uuid = ? AND gem_amount + ? >= 0",
                        ).use { statement ->
                            statement.setLong(1, amount)
                            statement.setString(2, uuid.toString())
                            statement.setLong(3, amount)
                            statement.executeUpdate()
                        }
                val balance = if (changed == 1) balance(connection, uuid) else null
                connection.commit()
                balance
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }

    fun purchase(
        uuid: UUID,
        product: String,
        cost: Long,
    ): UUID? {
        require(cost > 0L) { "Cost must be positive" }
        return DriverManager.getConnection(url).use { connection ->
            connection.autoCommit = false
            try {
                val debited =
                    connection
                        .prepareStatement(
                            "UPDATE gems SET gem_amount = gem_amount - ? WHERE player_uuid = ? AND gem_amount >= ?",
                        ).use { statement ->
                            statement.setLong(1, cost)
                            statement.setString(2, uuid.toString())
                            statement.setLong(3, cost)
                            statement.executeUpdate() == 1
                        }
                if (!debited) {
                    connection.rollback()
                    return null
                }
                val transactionId = UUID.randomUUID()
                connection
                    .prepareStatement(
                        "INSERT INTO gem_transactions (transaction_id, player_uuid, player_name, product, amount, created_at) SELECT ?, player_uuid, player_name, ?, ?, ? FROM gems WHERE player_uuid = ?",
                    ).use { statement ->
                        statement.setString(1, transactionId.toString())
                        statement.setString(2, product)
                        statement.setLong(3, cost)
                        statement.setLong(4, System.currentTimeMillis())
                        statement.setString(5, uuid.toString())
                        statement.executeUpdate()
                    }
                connection.commit()
                transactionId
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }

    fun balance(uuid: UUID): Long? = DriverManager.getConnection(url).use { balance(it, uuid) }

    private fun balance(
        connection: java.sql.Connection,
        uuid: UUID,
    ): Long? =
        connection.prepareStatement("SELECT gem_amount FROM gems WHERE player_uuid = ?").use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }
}

data class GemPlayer(
    val uuid: UUID,
    val name: String,
    val balance: Long,
)

data class GemTransaction(
    val id: UUID,
    val product: String,
    val amount: Long,
    val createdAt: Long,
)
