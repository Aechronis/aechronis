package net.aechronis.logger.repos

import net.aechronis.logger.db.Database
import net.aechronis.logger.objects.StorageChange
import net.aechronis.logger.objects.StorageChangeAction
import net.aechronis.logger.params.LookupParams
import net.aechronis.logger.utils.ItemCodec
import net.aechronis.logger.utils.LogMetadata
import net.aechronis.logger.utils.bindAll
import net.aechronis.logger.utils.getNullableInt
import net.aechronis.logger.utils.placeholders
import net.aechronis.logger.utils.setNullableInt
import net.aechronis.logger.utils.setNullableString
import net.minestom.server.item.ItemStack
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StorageChange(
    private val database: Database,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val table = database.storageTableName
    private val selectColumns =
        "id, ts, player_uuid, player_name, storage_id, action, item_data, amount, slot, source, origin, rolled_back"
    private val pendingWrites = ConcurrentHashMap.newKeySet<CompletableFuture<Void>>()

    private val insertSql =
        """
        INSERT INTO "$table"
            (ts, player_uuid, player_name, storage_id, action, item_data, amount, slot, source, origin)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    fun insertAsync(change: StorageChange): CompletableFuture<Void> {
        val future = CompletableFuture.runAsync({ insert(change) }, executor)
        pendingWrites += future
        future.whenComplete { _, _ -> pendingWrites -= future }
        return future
    }

    fun flushAsync(): CompletableFuture<Void> = CompletableFuture.allOf(*pendingWrites.toTypedArray())

    fun insertAllAsync(changes: List<StorageChange>): CompletableFuture<Void> {
        if (changes.isEmpty()) return CompletableFuture.completedFuture(null)
        val future =
            CompletableFuture.runAsync({
                database.dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        connection.prepareStatement(insertSql).use { statement ->
                            changes.forEach { change ->
                                bindInsert(statement, change)
                                statement.addBatch()
                            }
                            statement.executeBatch()
                        }
                        connection.commit()
                    } catch (exception: Exception) {
                        connection.rollback()
                        throw exception
                    }
                }
            }, executor)
        pendingWrites += future
        future.whenComplete { _, _ -> pendingWrites -= future }
        return future
    }

    fun withdrawAsync(
        storageId: String,
        item: ItemStack,
        amount: Int,
        slot: Int? = null,
        playerUuid: UUID? = null,
        playerName: String? = null,
        source: String = LogMetadata.LOGGER,
        origin: String = LogMetadata.LOGGER,
    ): CompletableFuture<Void> =
        insertAsync(
            StorageChange(
                timestamp = System.currentTimeMillis(),
                storageId = storageId,
                action = StorageChangeAction.WITHDRAW,
                item = item,
                amount = amount,
                slot = slot,
                playerUuid = playerUuid,
                playerName = playerName,
                source = source,
                origin = origin,
            ),
        )

    fun depositAsync(
        storageId: String,
        item: ItemStack,
        amount: Int,
        slot: Int? = null,
        playerUuid: UUID? = null,
        playerName: String? = null,
        source: String = LogMetadata.LOGGER,
        origin: String = LogMetadata.LOGGER,
    ): CompletableFuture<Void> =
        insertAsync(
            StorageChange(
                timestamp = System.currentTimeMillis(),
                storageId = storageId,
                action = StorageChangeAction.DEPOSIT,
                item = item,
                amount = amount,
                slot = slot,
                playerUuid = playerUuid,
                playerName = playerName,
                source = source,
                origin = origin,
            ),
        )

    private fun insert(change: StorageChange) {
        require(change.amount > 0) { "storage change amount must be positive" }
        database.dataSource.connection.use { conn ->
            conn.prepareStatement(insertSql).use { ps ->
                bindInsert(ps, change)
                ps.executeUpdate()
            }
        }
    }

    private fun bindInsert(
        statement: PreparedStatement,
        change: StorageChange,
    ) {
        require(change.amount > 0) { "storage change amount must be positive" }
        statement.setLong(1, change.timestamp)
        statement.setNullableString(2, change.playerUuid?.toString())
        statement.setNullableString(3, change.playerName)
        statement.setString(4, change.storageId)
        statement.setString(5, change.action.value)
        statement.setBytes(6, ItemCodec.encodeItem(change.item))
        statement.setInt(7, change.amount)
        statement.setNullableInt(8, change.slot)
        statement.setString(9, change.source)
        statement.setString(10, change.origin)
    }

    fun searchForOperationAsync(
        params: LookupParams,
        targetTs: Long,
        actions: Set<StorageChangeAction>?,
        rolledBack: Boolean,
        limit: Int,
    ): CompletableFuture<List<StorageChange>> =
        flushAsync().thenApplyAsync({
            val sql = StringBuilder("SELECT $selectColumns FROM \"$table\" WHERE ts >= ? AND rolled_back = ?")
            val args = mutableListOf<Any>(targetTs, rolledBack)
            params.until?.let {
                sql.append(" AND ts <= ?")
                args += it
            }
            if (params.users.isNotEmpty()) {
                sql.append(" AND LOWER(player_name) IN (${placeholders(params.users.size)})")
                params.users.forEach { args += it.lowercase() }
            }
            params.source?.let {
                sql.append(" AND LOWER(source) = ?")
                args += it.lowercase()
            }
            params.origin?.let {
                sql.append(" AND LOWER(origin) = ?")
                args += it.lowercase()
            }
            actions?.let {
                sql.append(" AND action IN (${placeholders(it.size)})")
                it.forEach { action -> args += action.value }
            }
            sql.append(if (rolledBack) " ORDER BY ts ASC, id ASC" else " ORDER BY ts DESC, id DESC")
            val wanted = if (limit == Int.MAX_VALUE) Int.MAX_VALUE else limit + 1
            val pageSize = minOf(wanted, 512)
            val rows = mutableListOf<StorageChange>()
            var offset = 0
            database.dataSource.connection.use { connection ->
                connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                connection.autoCommit = false
                while (rows.size < wanted) {
                    var fetched = 0
                    connection.prepareStatement("$sql LIMIT ? OFFSET ?").use { statement ->
                        statement.bindAll(args + pageSize + offset)
                        statement.executeQuery().use { results ->
                            while (results.next()) {
                                fetched++
                                val change = mapRow(results)
                                val key =
                                    change.item
                                        .material()
                                        .key()
                                        .asString()
                                if ((params.include.isEmpty() || key in params.include) && key !in params.exclude) {
                                    rows += change
                                    if (rows.size == wanted) break
                                }
                            }
                        }
                    }
                    if (fetched < pageSize) break
                    offset += fetched
                }
                connection.commit()
            }
            rows
        }, executor)

    private fun mapRow(results: ResultSet): StorageChange =
        StorageChange(
            id = results.getLong("id"),
            timestamp = results.getLong("ts"),
            playerUuid = results.getString("player_uuid")?.let(UUID::fromString),
            playerName = results.getString("player_name"),
            storageId = results.getString("storage_id"),
            action = StorageChangeAction.fromValue(results.getString("action")),
            item = ItemCodec.decodeItem(results.getBytes("item_data")),
            amount = results.getInt("amount"),
            slot = results.getNullableInt("slot"),
            source = results.getString("source"),
            origin = results.getString("origin"),
            rolledBack = results.getBoolean("rolled_back"),
        )

    override fun close() {
        executor.close()
    }
}
