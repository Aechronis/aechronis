package net.aechronis.logger.db

import net.aechronis.logger.objects.RollbackChange
import net.aechronis.logger.objects.RollbackChangeKind
import net.aechronis.logger.objects.RollbackOperation
import net.aechronis.logger.objects.RollbackStatus
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RollbackRepository(
    private val database: Database,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    fun insertOperationAsync(
        operation: RollbackOperation,
        changes: List<RollbackChange>,
    ): CompletableFuture<Long> = CompletableFuture.supplyAsync({ insertOperation(operation, changes) }, executor)

    private fun insertOperation(
        operation: RollbackOperation,
        changes: List<RollbackChange>,
    ): Long {
        database.dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val operationId = insertOperationRow(conn, operation)
                insertChangeRows(conn, operationId, changes)
                conn.commit()
                return operationId
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun insertOperationRow(
        conn: java.sql.Connection,
        operation: RollbackOperation,
    ): Long {
        conn
            .prepareStatement(
                """
                INSERT INTO rollback_operation
                    (ts, actor_uuid, actor_name, instance_uuid, query_desc, target_ts, status, block_change_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setLong(1, operation.timestamp)
                ps.setString(2, operation.actorUuid.toString())
                ps.setString(3, operation.actorName)
                ps.setString(4, operation.instanceUuid.toString())
                ps.setString(5, operation.queryDesc)
                ps.setLong(6, operation.targetTs)
                ps.setString(7, operation.status.value)
                ps.setInt(8, operation.blockChangeCount)
                ps.executeUpdate()
                ps.generatedKeys.use { keys ->
                    keys.next()
                    return keys.getLong(1)
                }
            }
    }

    private fun insertChangeRows(
        conn: java.sql.Connection,
        operationId: Long,
        changes: List<RollbackChange>,
    ) {
        if (changes.isEmpty()) return
        conn
            .prepareStatement(
                """
                INSERT INTO rollback_change
                    (operation_id, change_kind, x, y, z, before_block_state, before_block_nbt,
                     after_block_state, after_block_nbt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                for (change in changes) {
                    ps.setLong(1, operationId)
                    ps.setString(2, change.changeKind.value)
                    setNullableInt(ps, 3, change.x)
                    setNullableInt(ps, 4, change.y)
                    setNullableInt(ps, 5, change.z)
                    setNullableString(ps, 6, change.beforeBlockState)
                    setNullableBytes(ps, 7, change.beforeBlockNbt)
                    setNullableString(ps, 8, change.afterBlockState)
                    setNullableBytes(ps, 9, change.afterBlockNbt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
    }

    private fun setNullableInt(
        ps: java.sql.PreparedStatement,
        index: Int,
        value: Int?,
    ) {
        if (value != null) ps.setInt(index, value) else ps.setNull(index, Types.INTEGER)
    }

    private fun setNullableString(
        ps: java.sql.PreparedStatement,
        index: Int,
        value: String?,
    ) {
        if (value != null) ps.setString(index, value) else ps.setNull(index, Types.VARCHAR)
    }

    private fun setNullableBytes(
        ps: java.sql.PreparedStatement,
        index: Int,
        value: ByteArray?,
    ) {
        if (value != null) ps.setBytes(index, value) else ps.setNull(index, Types.BLOB)
    }

    fun updateStatusAsync(
        operationId: Long,
        status: RollbackStatus,
    ): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            database.dataSource.connection.use { conn ->
                conn.prepareStatement("UPDATE rollback_operation SET status = ? WHERE id = ?").use { ps ->
                    ps.setString(1, status.value)
                    ps.setLong(2, operationId)
                    ps.executeUpdate()
                }
            }
        }, executor)

    fun findOperationAsync(operationId: Long): CompletableFuture<RollbackOperation?> =
        CompletableFuture.supplyAsync({
            database.dataSource.connection.use { conn ->
                conn
                    .prepareStatement(
                        "SELECT id, ts, actor_uuid, actor_name, instance_uuid, query_desc, target_ts, status, " +
                            "block_change_count FROM rollback_operation WHERE id = ?",
                    ).use { ps ->
                        ps.setLong(1, operationId)
                        ps.executeQuery().use { rs -> if (rs.next()) mapOperation(rs) else null }
                    }
            }
        }, executor)

    fun findChangesAsync(operationId: Long): CompletableFuture<List<RollbackChange>> =
        CompletableFuture.supplyAsync({
            val out = mutableListOf<RollbackChange>()
            database.dataSource.connection.use { conn ->
                conn
                    .prepareStatement(
                        "SELECT id, operation_id, change_kind, x, y, z, before_block_state, before_block_nbt, " +
                            "after_block_state, after_block_nbt FROM rollback_change WHERE operation_id = ?",
                    ).use { ps ->
                        ps.setLong(1, operationId)
                        ps.executeQuery().use { rs ->
                            while (rs.next()) out += mapChange(rs)
                        }
                    }
            }
            out
        }, executor)

    private fun mapOperation(rs: ResultSet): RollbackOperation =
        RollbackOperation(
            id = rs.getLong("id"),
            timestamp = rs.getLong("ts"),
            actorUuid = UUID.fromString(rs.getString("actor_uuid")),
            actorName = rs.getString("actor_name"),
            instanceUuid = UUID.fromString(rs.getString("instance_uuid")),
            queryDesc = rs.getString("query_desc"),
            targetTs = rs.getLong("target_ts"),
            status = RollbackStatus.fromValue(rs.getString("status")),
            blockChangeCount = rs.getInt("block_change_count"),
        )

    private fun mapChange(rs: ResultSet): RollbackChange =
        RollbackChange(
            id = rs.getLong("id"),
            operationId = rs.getLong("operation_id"),
            changeKind = RollbackChangeKind.fromValue(rs.getString("change_kind")),
            x = rs.getInt("x").let { if (rs.wasNull()) null else it },
            y = rs.getInt("y").let { if (rs.wasNull()) null else it },
            z = rs.getInt("z").let { if (rs.wasNull()) null else it },
            beforeBlockState = rs.getString("before_block_state"),
            beforeBlockNbt = rs.getBytes("before_block_nbt"),
            afterBlockState = rs.getString("after_block_state"),
            afterBlockNbt = rs.getBytes("after_block_nbt"),
        )

    override fun close() {
        executor.shutdown()
    }
}
