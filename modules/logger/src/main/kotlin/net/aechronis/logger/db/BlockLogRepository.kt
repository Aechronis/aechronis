package net.aechronis.logger.db

import net.aechronis.logger.BlockAction
import net.aechronis.logger.BlockLogEntry
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BlockLogRepository(
    private val database: Database,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val table = database.tableName

    private val insertSql =
        """
        INSERT INTO `$table`
            (ts, player_uuid, player_name, x, y, z, block_old, block_new, action)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    private val lookupSql =
        """
        SELECT ts, player_uuid, player_name, x, y, z, block_old, block_new, action
        FROM `$table`
        WHERE x = ? AND y = ? AND z = ?
        ORDER BY ts DESC
        LIMIT ?
        """.trimIndent()

    fun insertAsync(entry: BlockLogEntry): CompletableFuture<Void> = CompletableFuture.runAsync({ insert(entry) }, executor)

    private fun insert(entry: BlockLogEntry) {
        database.dataSource.connection.use { conn ->
            conn.prepareStatement(insertSql).use { ps ->
                ps.setLong(1, entry.timestamp)
                ps.setString(2, entry.playerUuid.toString())
                ps.setString(3, entry.playerName)
                ps.setInt(4, entry.x)
                ps.setInt(5, entry.y)
                ps.setInt(6, entry.z)
                ps.setString(7, entry.blockOld)
                ps.setString(8, entry.blockNew)
                ps.setByte(9, entry.action.id)
                ps.executeUpdate()
            }
        }
    }

    fun lookupAsync(
        x: Int,
        y: Int,
        z: Int,
        limit: Int = 10,
    ): CompletableFuture<List<BlockLogEntry>> = CompletableFuture.supplyAsync({ lookupSync(x, y, z, limit) }, executor)

    private fun lookupSync(
        x: Int,
        y: Int,
        z: Int,
        limit: Int,
    ): List<BlockLogEntry> {
        val out = mutableListOf<BlockLogEntry>()
        database.dataSource.connection.use { conn ->
            conn.prepareStatement(lookupSql).use { ps ->
                ps.setInt(1, x)
                ps.setInt(2, y)
                ps.setInt(3, z)
                ps.setInt(4, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        out +=
                            BlockLogEntry(
                                timestamp = rs.getLong("ts"),
                                playerUuid = UUID.fromString(rs.getString("player_uuid")),
                                playerName = rs.getString("player_name"),
                                x = rs.getInt("x"),
                                y = rs.getInt("y"),
                                z = rs.getInt("z"),
                                blockOld = rs.getString("block_old"),
                                blockNew = rs.getString("block_new"),
                                action = BlockAction.fromId(rs.getByte("action")),
                            )
                    }
                }
            }
        }
        return out
    }

    override fun close() {
        executor.shutdown()
    }
}
