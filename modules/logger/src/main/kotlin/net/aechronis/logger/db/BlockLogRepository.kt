package net.aechronis.logger.db

import net.aechronis.logger.objects.BlockAction
import net.aechronis.logger.objects.BlockLogEntry
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BlockLogRepository(
    private val database: Database,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val table = database.tableName

    private val selectColumns =
        "id, ts, player_uuid, player_name, x, y, z, block_old, block_new, action, " +
            "instance_uuid, block_old_state, block_new_state, block_old_nbt, block_new_nbt"

    private val insertSql =
        """
        INSERT INTO `$table`
            (ts, player_uuid, player_name, x, y, z, block_old, block_new, action,
             instance_uuid, block_old_state, block_new_state, block_old_nbt, block_new_nbt)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    private val lookupSql =
        """
        SELECT $selectColumns
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
                ps.setNullableString(10, entry.instanceUuid?.toString())
                ps.setNullableString(11, entry.blockOldState)
                ps.setNullableString(12, entry.blockNewState)
                ps.setNullableBytes(13, entry.blockOldNbt)
                ps.setNullableBytes(14, entry.blockNewNbt)
                ps.executeUpdate()
            }
        }
    }

    private fun PreparedStatement.setNullableString(
        index: Int,
        value: String?,
    ) {
        if (value != null) setString(index, value) else setNull(index, Types.VARCHAR)
    }

    private fun PreparedStatement.setNullableBytes(
        index: Int,
        value: ByteArray?,
    ) {
        if (value != null) setBytes(index, value) else setNull(index, Types.BLOB)
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
                    while (rs.next()) out += mapRow(rs)
                }
            }
        }
        return out
    }

    private fun mapRow(rs: ResultSet): BlockLogEntry =
        BlockLogEntry(
            id = rs.getLong("id"),
            timestamp = rs.getLong("ts"),
            playerUuid = UUID.fromString(rs.getString("player_uuid")),
            playerName = rs.getString("player_name"),
            x = rs.getInt("x"),
            y = rs.getInt("y"),
            z = rs.getInt("z"),
            blockOld = rs.getString("block_old"),
            blockNew = rs.getString("block_new"),
            action = BlockAction.fromId(rs.getByte("action")),
            instanceUuid = rs.getString("instance_uuid")?.let(UUID::fromString),
            blockOldState = rs.getString("block_old_state"),
            blockNewState = rs.getString("block_new_state"),
            blockOldNbt = rs.getBytes("block_old_nbt"),
            blockNewNbt = rs.getBytes("block_new_nbt"),
        )

    override fun close() {
        executor.shutdown()
    }
}
