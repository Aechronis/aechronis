package net.aechronis.gems

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
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
                    "MERGE INTO gems (player_uuid, player_name, gem_amount) KEY(player_uuid) VALUES (?, ?, COALESCE((SELECT gem_amount FROM gems WHERE player_uuid = ?), 0))",
                ).use { statement ->
                    statement.setString(1, uuid.toString())
                    statement.setString(2, name)
                    statement.setString(3, uuid.toString())
                    statement.executeUpdate()
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

    fun findPlayerByUuid(uuid: UUID): GemPlayer? =
        DriverManager.getConnection(url).use { connection ->
            connection
                .prepareStatement(
                    "SELECT player_uuid, player_name, gem_amount FROM gems WHERE player_uuid = ?",
                ).use { statement ->
                    statement.setString(1, uuid.toString())
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

    /** Credits a CraftingStore refund exactly once for the supplied reference. */
    fun refund(
        uuid: UUID,
        amount: Long,
        reference: String,
    ): Boolean {
        require(amount > 0L) { "Refund amount must be positive" }
        val product = "craftingstore-refund:${reference.take(48)}"
        return DriverManager.getConnection(url).use { connection ->
            connection.autoCommit = false
            try {
                connection
                    .prepareStatement(
                        "SELECT 1 FROM gem_transactions WHERE player_uuid = ? AND product = ? LIMIT 1",
                    ).use { statement ->
                        statement.setString(1, uuid.toString())
                        statement.setString(2, product)
                        statement.executeQuery().use { rows ->
                            if (rows.next()) {
                                connection.commit()
                                return@use true
                            }
                        }
                    }
                val changed =
                    connection
                        .prepareStatement(
                            "UPDATE gems SET gem_amount = gem_amount + ? WHERE player_uuid = ?",
                        ).use { statement ->
                            statement.setLong(1, amount)
                            statement.setString(2, uuid.toString())
                            statement.executeUpdate() == 1
                        }
                if (!changed) {
                    connection.rollback()
                    return@use false
                }
                val inserted =
                    connection
                        .prepareStatement(
                            "INSERT INTO gem_transactions (transaction_id, player_uuid, player_name, product, amount, created_at) SELECT ?, player_uuid, player_name, ?, ?, ? FROM gems WHERE player_uuid = ?",
                        ).use { statement ->
                            statement.setString(1, UUID.randomUUID().toString())
                            statement.setString(2, product)
                            statement.setLong(3, amount)
                            statement.setLong(4, System.currentTimeMillis())
                            statement.setString(5, uuid.toString())
                            statement.executeUpdate() == 1
                        }
                if (inserted) connection.commit() else connection.rollback()
                inserted
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
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
