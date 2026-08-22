package net.aechronis.logger.repos

import net.aechronis.logger.db.Database
import net.aechronis.logger.objects.EntityChangeAction
import net.aechronis.logger.objects.RollbackChange
import net.aechronis.logger.objects.RollbackChangeKind
import net.aechronis.logger.objects.RollbackOperation
import net.aechronis.logger.objects.RollbackOperationKind
import net.aechronis.logger.objects.RollbackStatus
import net.aechronis.logger.objects.StorageChangeAction
import net.aechronis.logger.utils.getNullableInt
import net.aechronis.logger.utils.setNullableBytes
import net.aechronis.logger.utils.setNullableInt
import net.aechronis.logger.utils.setNullableString
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class Rollback(
    private val database: Database,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val blockTable = database.tableName
    private val storageTable = database.storageTableName
    private val inventoryTable = database.inventoryChangeTableName
    private val entityTable = database.entityChangeTableName

    fun insertOperationAsync(
        operation: RollbackOperation,
        changes: List<RollbackChange>,
    ): CompletableFuture<Long> = CompletableFuture.supplyAsync({ insertOperation(operation, changes) }, executor)

    private fun insertOperation(
        operation: RollbackOperation,
        changes: List<RollbackChange>,
    ): Long =
        transaction { connection ->
            val operationId = insertOperationRow(connection, operation)
            insertChangeRows(connection, operationId, changes)
            operationId
        }

    private fun insertOperationRow(
        connection: Connection,
        operation: RollbackOperation,
    ): Long {
        connection
            .prepareStatement(
                """
                INSERT INTO rollback_operation
                    (ts, completed_ts, actor_uuid, actor_name, instance_uuid, operation_kind, query_desc,
                     target_ts, safe_mode, status, block_change_count, skipped_change_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, operation.timestamp)
                if (operation.completedTimestamp ==
                    null
                ) {
                    statement.setNull(2, Types.BIGINT)
                } else {
                    statement.setLong(2, operation.completedTimestamp)
                }
                statement.setString(3, operation.actorUuid.toString())
                statement.setString(4, operation.actorName)
                statement.setString(5, operation.instanceUuid.toString())
                statement.setString(6, operation.kind.value)
                statement.setString(7, operation.queryDesc)
                statement.setLong(8, operation.targetTs)
                statement.setBoolean(9, operation.safeMode)
                statement.setString(10, operation.status.value)
                statement.setInt(11, operation.blockChangeCount)
                statement.setInt(12, operation.skippedChangeCount)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    check(keys.next()) { "rollback operation did not return a generated ID" }
                    return keys.getLong(1)
                }
            }
    }

    private fun insertChangeRows(
        connection: Connection,
        operationId: Long,
        changes: List<RollbackChange>,
    ) {
        if (changes.isEmpty()) return
        connection
            .prepareStatement(
                """
                INSERT INTO rollback_change
                    (operation_id, sequence_no, block_log_id, storage_log_id, inventory_log_id, entity_log_id, change_kind, applied, x, y, z,
                     before_block_state, before_block_nbt, after_block_state, after_block_nbt,
                      storage_source, storage_id, storage_action, item_data, amount, storage_slot,
                     inventory_player_uuid, inventory_slot, before_item_data, after_item_data,
                     entity_uuid, entity_type, entity_action, entity_x, entity_y, entity_z, entity_yaw, entity_pitch,
                      entity_velocity_x, entity_velocity_y, entity_velocity_z, entity_tag_data,
                      before_block_handler, after_block_handler)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                for (change in changes) {
                    statement.setLong(1, operationId)
                    statement.setInt(2, change.sequence)
                    if (change.blockLogId == null) statement.setNull(3, Types.BIGINT) else statement.setLong(3, change.blockLogId)
                    if (change.storageLogId == null) statement.setNull(4, Types.BIGINT) else statement.setLong(4, change.storageLogId)
                    if (change.inventoryLogId == null) statement.setNull(5, Types.BIGINT) else statement.setLong(5, change.inventoryLogId)
                    if (change.entityLogId == null) statement.setNull(6, Types.BIGINT) else statement.setLong(6, change.entityLogId)
                    statement.setString(7, change.changeKind.value)
                    statement.setBoolean(8, change.applied)
                    statement.setNullableInt(9, change.x)
                    statement.setNullableInt(10, change.y)
                    statement.setNullableInt(11, change.z)
                    statement.setNullableString(12, change.beforeBlockState)
                    statement.setNullableBytes(13, change.beforeBlockNbt)
                    statement.setNullableString(14, change.afterBlockState)
                    statement.setNullableBytes(15, change.afterBlockNbt)
                    statement.setNullableString(16, change.storageSource)
                    statement.setNullableString(17, change.storageId)
                    statement.setNullableString(18, change.storageAction?.value)
                    statement.setNullableBytes(19, change.itemData)
                    statement.setNullableInt(20, change.amount)
                    statement.setNullableInt(21, change.storageSlot)
                    statement.setNullableString(22, change.inventoryPlayerUuid?.toString())
                    statement.setNullableInt(23, change.inventorySlot)
                    statement.setNullableBytes(24, change.beforeItemData)
                    statement.setNullableBytes(25, change.afterItemData)
                    statement.setNullableString(26, change.entityUuid?.toString())
                    statement.setNullableString(27, change.entityType)
                    statement.setNullableString(28, change.entityAction?.value)
                    statement.setObject(29, change.entityPosition?.x())
                    statement.setObject(30, change.entityPosition?.y())
                    statement.setObject(31, change.entityPosition?.z())
                    statement.setObject(32, change.entityPosition?.yaw())
                    statement.setObject(33, change.entityPosition?.pitch())
                    statement.setObject(34, change.entityVelocity?.x())
                    statement.setObject(35, change.entityVelocity?.y())
                    statement.setObject(36, change.entityVelocity?.z())
                    statement.setNullableBytes(37, change.entityTagData)
                    statement.setNullableString(38, change.beforeBlockHandler)
                    statement.setNullableString(39, change.afterBlockHandler)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
    }

    fun transitionStatusAsync(
        operationId: Long,
        expected: RollbackStatus,
        status: RollbackStatus,
    ): CompletableFuture<Boolean> =
        CompletableFuture.supplyAsync({
            database.dataSource.connection.use { connection ->
                connection
                    .prepareStatement("UPDATE rollback_operation SET status = ? WHERE id = ? AND status = ?")
                    .use { statement ->
                        statement.setString(1, status.value)
                        statement.setLong(2, operationId)
                        statement.setString(3, expected.value)
                        statement.executeUpdate() == 1
                    }
            }
        }, executor)

    fun startOperationAsync(
        operationId: Long,
        expectedStatus: RollbackStatus,
        targetStatus: RollbackStatus,
        changes: List<RollbackChange>,
        expectedRolledBack: Boolean,
    ): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            transaction { connection ->
                checkMarkers(connection, changes, expectedRolledBack)
                connection
                    .prepareStatement("UPDATE rollback_operation SET status = ? WHERE id = ? AND status = ?")
                    .use { statement ->
                        statement.setString(1, targetStatus.value)
                        statement.setLong(2, operationId)
                        statement.setString(3, expectedStatus.value)
                        check(statement.executeUpdate() == 1) { "rollback operation $operationId changed state concurrently" }
                    }
            }
        }, executor)

    fun markInterruptedOperationsAsync(): CompletableFuture<Int> =
        CompletableFuture.supplyAsync({
            database.dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "UPDATE rollback_operation SET status = ? WHERE status IN (?, ?, ?)",
                    ).use { statement ->
                        statement.setString(1, RollbackStatus.RECOVERY_REQUIRED.value)
                        statement.setString(2, RollbackStatus.APPLYING.value)
                        statement.setString(3, RollbackStatus.UNDOING.value)
                        statement.setString(4, RollbackStatus.REDOING.value)
                        statement.executeUpdate()
                    }
            }
        }, executor)

    fun hasRecoveryRequiredAsync(
        instanceUuid: UUID,
        includeGlobalState: Boolean = false,
    ): CompletableFuture<Boolean> =
        CompletableFuture.supplyAsync({
            database.dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT 1
                        FROM rollback_operation ro
                        WHERE ro.status IN (?, ?, ?, ?)
                          AND (
                            ro.instance_uuid = ?
                            OR (
                              ? = TRUE
                              AND EXISTS (
                                SELECT 1 FROM rollback_change rc
                                WHERE rc.operation_id = ro.id
                                  AND rc.change_kind IN (?, ?)
                              )
                            )
                          )
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, RollbackStatus.RECOVERY_REQUIRED.value)
                        statement.setString(2, RollbackStatus.APPLYING.value)
                        statement.setString(3, RollbackStatus.UNDOING.value)
                        statement.setString(4, RollbackStatus.REDOING.value)
                        statement.setString(5, instanceUuid.toString())
                        statement.setBoolean(6, includeGlobalState)
                        statement.setString(7, RollbackChangeKind.STORAGE.value)
                        statement.setString(8, RollbackChangeKind.INVENTORY.value)
                        statement.executeQuery().use { it.next() }
                    }
            }
        }, executor)

    fun acknowledgeRecoveryAsync(): CompletableFuture<Int> =
        CompletableFuture.supplyAsync({
            database.dataSource.connection.use { connection ->
                connection
                    .prepareStatement("UPDATE rollback_operation SET status = ? WHERE status IN (?, ?, ?, ?)")
                    .use { statement ->
                        statement.setString(1, RollbackStatus.FAILED.value)
                        statement.setString(2, RollbackStatus.RECOVERY_REQUIRED.value)
                        statement.setString(3, RollbackStatus.APPLYING.value)
                        statement.setString(4, RollbackStatus.UNDOING.value)
                        statement.setString(5, RollbackStatus.REDOING.value)
                        statement.executeUpdate()
                    }
            }
        }, executor)

    fun markRecoveryRequiredAsync(operationId: Long): CompletableFuture<Boolean> =
        CompletableFuture.supplyAsync({
            database.dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "UPDATE rollback_operation SET status = ? WHERE id = ? AND status IN (?, ?, ?)",
                    ).use { statement ->
                        statement.setString(1, RollbackStatus.RECOVERY_REQUIRED.value)
                        statement.setLong(2, operationId)
                        statement.setString(3, RollbackStatus.APPLYING.value)
                        statement.setString(4, RollbackStatus.UNDOING.value)
                        statement.setString(5, RollbackStatus.REDOING.value)
                        statement.executeUpdate() == 1
                    }
            }
        }, executor)

    fun completeOperationAsync(
        operationId: Long,
        appliedChanges: List<RollbackChange>,
        rolledBack: Boolean,
        skippedCount: Int,
    ): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            transaction { connection ->
                updateMarkers(connection, appliedChanges, target = rolledBack)
                markChangesApplied(connection, operationId, appliedChanges)
                connection
                    .prepareStatement(
                        """
                        UPDATE rollback_operation
                        SET status = ?, completed_ts = ?, block_change_count = ?, skipped_change_count = ?
                        WHERE id = ? AND status = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, RollbackStatus.APPLIED.value)
                        statement.setLong(2, System.currentTimeMillis())
                        statement.setInt(3, appliedChanges.size)
                        statement.setInt(4, skippedCount)
                        statement.setLong(5, operationId)
                        statement.setString(6, RollbackStatus.APPLYING.value)
                        check(statement.executeUpdate() == 1) { "rollback operation $operationId changed state concurrently" }
                    }
            }
        }, executor)

    fun completeReplayAsync(
        operation: RollbackOperation,
        expectedStatus: RollbackStatus,
        targetStatus: RollbackStatus,
        targetRolledBack: Boolean,
    ): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            val changes = findChanges(operation.id, appliedOnly = true)
            transaction { connection ->
                updateMarkers(connection, changes, target = targetRolledBack)
                connection
                    .prepareStatement("UPDATE rollback_operation SET status = ?, completed_ts = ? WHERE id = ? AND status = ?")
                    .use { statement ->
                        statement.setString(1, targetStatus.value)
                        statement.setLong(2, System.currentTimeMillis())
                        statement.setLong(3, operation.id)
                        statement.setString(4, expectedStatus.value)
                        check(statement.executeUpdate() == 1) { "rollback operation ${operation.id} changed state concurrently" }
                    }
            }
        }, executor)

    private fun updateMarkers(
        connection: Connection,
        changes: List<RollbackChange>,
        target: Boolean,
    ) {
        updateTableMarkers(connection, blockTable, changes.mapNotNull { it.blockLogId }, target)
        updateTableMarkers(connection, storageTable, changes.mapNotNull { it.storageLogId }, target)
        updateTableMarkers(connection, inventoryTable, changes.mapNotNull { it.inventoryLogId }, target)
        updateTableMarkers(connection, entityTable, changes.mapNotNull { it.entityLogId }, target)
    }

    private fun checkMarkers(
        connection: Connection,
        changes: List<RollbackChange>,
        expected: Boolean,
    ) {
        checkTableMarkers(connection, blockTable, changes.mapNotNull { it.blockLogId }, expected)
        checkTableMarkers(connection, storageTable, changes.mapNotNull { it.storageLogId }, expected)
        checkTableMarkers(connection, inventoryTable, changes.mapNotNull { it.inventoryLogId }, expected)
        checkTableMarkers(connection, entityTable, changes.mapNotNull { it.entityLogId }, expected)
    }

    private fun checkTableMarkers(
        connection: Connection,
        table: String,
        logIds: List<Long>,
        expected: Boolean,
    ) {
        if (logIds.isEmpty()) return
        connection.prepareStatement("SELECT rolled_back FROM \"$table\" WHERE id = ?").use { statement ->
            for (logId in logIds.distinct()) {
                statement.setLong(1, logId)
                statement.executeQuery().use { results ->
                    check(results.next() && results.getBoolean("rolled_back") == expected) { "history row changed after preview" }
                }
            }
        }
    }

    private fun updateTableMarkers(
        connection: Connection,
        table: String,
        logIds: List<Long>,
        target: Boolean,
    ) {
        if (logIds.isEmpty()) return
        connection.prepareStatement("UPDATE \"$table\" SET rolled_back = ? WHERE id = ? AND rolled_back = ?").use { statement ->
            for (logId in logIds) {
                statement.setBoolean(1, target)
                statement.setLong(2, logId)
                statement.setBoolean(3, !target)
                statement.addBatch()
            }
            statement.executeBatch().forEach { count -> check(count == 1) { "history row changed concurrently" } }
        }
    }

    private fun markChangesApplied(
        connection: Connection,
        operationId: Long,
        changes: List<RollbackChange>,
    ) {
        if (changes.isEmpty()) return
        val sequences = changes.map(RollbackChange::sequence).distinct().sorted()
        val ranges = mutableListOf<IntRange>()
        var rangeStart = sequences.first()
        var rangeEnd = rangeStart
        for (sequence in sequences.drop(1)) {
            if (sequence == rangeEnd + 1) {
                rangeEnd = sequence
            } else {
                ranges += rangeStart..rangeEnd
                rangeStart = sequence
                rangeEnd = sequence
            }
        }
        ranges += rangeStart..rangeEnd
        connection
            .prepareStatement(
                "UPDATE rollback_change SET applied = TRUE " +
                    "WHERE operation_id = ? AND sequence_no BETWEEN ? AND ? AND applied = FALSE",
            ).use { statement ->
                for (range in ranges) {
                    statement.setLong(1, operationId)
                    statement.setInt(2, range.first)
                    statement.setInt(3, range.last)
                    val updated = statement.executeUpdate()
                    check(updated == range.last - range.first + 1) { "rollback change row is missing or already applied" }
                }
            }
    }

    fun updateStatusAsync(
        operationId: Long,
        status: RollbackStatus,
    ): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            database.dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE rollback_operation SET status = ? WHERE id = ?").use { statement ->
                    statement.setString(1, status.value)
                    statement.setLong(2, operationId)
                    statement.executeUpdate()
                }
            }
        }, executor)

    fun findOperationAsync(operationId: Long): CompletableFuture<RollbackOperation?> =
        CompletableFuture.supplyAsync({ findOperation(operationId) }, executor)

    fun findLatestOperationAsync(actorUuid: UUID): CompletableFuture<RollbackOperation?> =
        CompletableFuture.supplyAsync({
            database.dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "SELECT $OPERATION_COLUMNS FROM rollback_operation " +
                            "WHERE actor_uuid = ? AND status IN (?, ?) ORDER BY id DESC LIMIT 1",
                    ).use { statement ->
                        statement.setString(1, actorUuid.toString())
                        statement.setString(2, RollbackStatus.APPLIED.value)
                        statement.setString(3, RollbackStatus.UNDONE.value)
                        statement.executeQuery().use { results -> if (results.next()) mapOperation(results) else null }
                    }
            }
        }, executor)

    private fun findOperation(operationId: Long): RollbackOperation? =
        database.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT $OPERATION_COLUMNS FROM rollback_operation WHERE id = ?").use { statement ->
                statement.setLong(1, operationId)
                statement.executeQuery().use { results -> if (results.next()) mapOperation(results) else null }
            }
        }

    fun findChangesAsync(
        operationId: Long,
        appliedOnly: Boolean = false,
    ): CompletableFuture<List<RollbackChange>> = CompletableFuture.supplyAsync({ findChanges(operationId, appliedOnly) }, executor)

    private fun findChanges(
        operationId: Long,
        appliedOnly: Boolean,
    ): List<RollbackChange> {
        val changes = mutableListOf<RollbackChange>()
        val appliedClause = if (appliedOnly) " AND applied = TRUE" else ""
        database.dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT $CHANGE_COLUMNS FROM rollback_change " +
                        "WHERE operation_id = ?$appliedClause ORDER BY sequence_no ASC, id ASC",
                ).use { statement ->
                    statement.setLong(1, operationId)
                    statement.executeQuery().use { results -> while (results.next()) changes += mapChange(results) }
                }
        }
        return changes
    }

    private fun mapOperation(results: ResultSet): RollbackOperation =
        RollbackOperation(
            id = results.getLong("id"),
            timestamp = results.getLong("ts"),
            completedTimestamp = results.getLong("completed_ts").takeUnless { results.wasNull() },
            actorUuid = UUID.fromString(results.getString("actor_uuid")),
            actorName = results.getString("actor_name"),
            instanceUuid = UUID.fromString(results.getString("instance_uuid")),
            kind = RollbackOperationKind.fromValue(results.getString("operation_kind")),
            queryDesc = results.getString("query_desc"),
            targetTs = results.getLong("target_ts"),
            safeMode = results.getBoolean("safe_mode"),
            status = RollbackStatus.fromValue(results.getString("status")),
            blockChangeCount = results.getInt("block_change_count"),
            skippedChangeCount = results.getInt("skipped_change_count"),
        )

    private fun mapChange(results: ResultSet): RollbackChange =
        RollbackChange(
            id = results.getLong("id"),
            operationId = results.getLong("operation_id"),
            sequence = results.getInt("sequence_no"),
            blockLogId = results.getLong("block_log_id").takeUnless { results.wasNull() },
            storageLogId = results.getLong("storage_log_id").takeUnless { results.wasNull() },
            inventoryLogId = results.getLong("inventory_log_id").takeUnless { results.wasNull() },
            entityLogId = results.getLong("entity_log_id").takeUnless { results.wasNull() },
            changeKind = RollbackChangeKind.fromValue(results.getString("change_kind")),
            applied = results.getBoolean("applied"),
            x = results.getNullableInt("x"),
            y = results.getNullableInt("y"),
            z = results.getNullableInt("z"),
            beforeBlockState = results.getString("before_block_state"),
            beforeBlockNbt = results.getBytes("before_block_nbt"),
            beforeBlockHandler = results.getString("before_block_handler"),
            afterBlockState = results.getString("after_block_state"),
            afterBlockNbt = results.getBytes("after_block_nbt"),
            afterBlockHandler = results.getString("after_block_handler"),
            storageSource = results.getString("storage_source"),
            storageId = results.getString("storage_id"),
            storageAction = results.getString("storage_action")?.let(StorageChangeAction::fromValue),
            itemData = results.getBytes("item_data"),
            amount = results.getNullableInt("amount"),
            storageSlot = results.getNullableInt("storage_slot"),
            inventoryPlayerUuid = results.getString("inventory_player_uuid")?.let(UUID::fromString),
            inventorySlot = results.getNullableInt("inventory_slot"),
            beforeItemData = results.getBytes("before_item_data"),
            afterItemData = results.getBytes("after_item_data"),
            entityUuid = results.getString("entity_uuid")?.let(UUID::fromString),
            entityType = results.getString("entity_type"),
            entityAction = results.getString("entity_action")?.let(EntityChangeAction::fromValue),
            entityPosition =
                results.getDouble("entity_x").let { x ->
                    if (results.wasNull()) {
                        null
                    } else {
                        Pos(
                            x,
                            results.getDouble("entity_y"),
                            results.getDouble("entity_z"),
                            results.getFloat("entity_yaw"),
                            results.getFloat("entity_pitch"),
                        )
                    }
                },
            entityVelocity =
                results.getDouble("entity_velocity_x").let { x ->
                    if (results.wasNull()) {
                        null
                    } else {
                        Vec(
                            x,
                            results.getDouble("entity_velocity_y"),
                            results.getDouble("entity_velocity_z"),
                        )
                    }
                },
            entityTagData = results.getBytes("entity_tag_data"),
        )

    private fun <T> transaction(block: (Connection) -> T): T =
        database.dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }

    override fun close() {
        executor.close()
    }

    private companion object {
        const val OPERATION_COLUMNS =
            "id, ts, completed_ts, actor_uuid, actor_name, instance_uuid, operation_kind, query_desc, " +
                "target_ts, safe_mode, status, block_change_count, skipped_change_count"
        const val CHANGE_COLUMNS =
            "id, operation_id, sequence_no, block_log_id, storage_log_id, inventory_log_id, entity_log_id, " +
                "change_kind, applied, x, y, z, " +
                "before_block_state, before_block_nbt, after_block_state, after_block_nbt, " +
                "storage_source, storage_id, storage_action, item_data, amount, storage_slot, " +
                "inventory_player_uuid, inventory_slot, before_item_data, after_item_data, " +
                "entity_uuid, entity_type, entity_action, entity_x, entity_y, entity_z, entity_yaw, entity_pitch, " +
                "entity_velocity_x, entity_velocity_y, entity_velocity_z, entity_tag_data, " +
                "before_block_handler, after_block_handler"
    }
}
