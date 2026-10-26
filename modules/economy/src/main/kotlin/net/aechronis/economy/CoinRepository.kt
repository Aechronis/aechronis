package net.aechronis.economy

import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

class CoinRepository(
    databasePath: Path = Path.of("economy", "coins").toAbsolutePath().normalize(),
) : AutoCloseable {
    private val database = EconomyDatabase(databasePath)

    init {
        database.connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS coins (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        coin_amount BIGINT NOT NULL CHECK (coin_amount >= 0)
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    override fun close() = database.close()

    fun rememberPlayer(uuid: UUID) {
        database.connection().use { connection ->
            if (balance(connection, uuid) != null) return
            try {
                connection.prepareStatement("INSERT INTO coins (player_uuid, coin_amount) VALUES (?, 0)").use { statement ->
                    statement.setString(1, uuid.toString())
                    statement.executeUpdate()
                }
            } catch (exception: SQLException) {
                // A concurrent first join or reward may have created the account.
                if (exception.sqlState != "23505") throw exception
            }
        }
    }

    fun balance(uuid: UUID): Long? = database.connection().use { balance(it, uuid) }

    /** Atomic credit. Returns null for a missing account, invalid amount, or overflow. */
    fun add(
        uuid: UUID,
        amount: Long,
    ): Long? {
        if (amount <= 0L) return null
        return database.connection().use { connection ->
            connection.autoCommit = false
            try {
                val updated =
                    connection
                        .prepareStatement(
                            "UPDATE coins SET coin_amount = coin_amount + ? WHERE player_uuid = ? AND coin_amount <= ?",
                        ).use { statement ->
                            statement.setLong(1, amount)
                            statement.setString(2, uuid.toString())
                            statement.setLong(3, Long.MAX_VALUE - amount)
                            statement.executeUpdate() == 1
                        }
                val result = if (updated) balance(connection, uuid) else null
                connection.commit()
                result
            } catch (exception: Throwable) {
                connection.rollback()
                throw exception
            }
        }
    }

    /** Atomic debit. An invalid or unaffordable amount leaves the balance untouched. */
    fun remove(
        uuid: UUID,
        amount: Long,
    ): Boolean {
        if (amount <= 0L) return false
        return database.connection().use { connection ->
            connection
                .prepareStatement(
                    "UPDATE coins SET coin_amount = coin_amount - ? WHERE player_uuid = ? AND coin_amount >= ?",
                ).use { statement ->
                    statement.setLong(1, amount)
                    statement.setString(2, uuid.toString())
                    statement.setLong(3, amount)
                    statement.executeUpdate() == 1
                }
        }
    }

    private fun balance(
        connection: Connection,
        uuid: UUID,
    ): Long? =
        connection.prepareStatement("SELECT coin_amount FROM coins WHERE player_uuid = ?").use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong("coin_amount") else null }
        }
}
