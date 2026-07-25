package net.aechronis.logger.repos

import net.aechronis.logger.db.Database
import net.aechronis.logger.objects.InventoryChange
import net.aechronis.logger.objects.InventoryChangeAction
import net.aechronis.logger.params.LookupParams
import net.aechronis.logger.utils.ItemCodec
import net.aechronis.logger.utils.bindAll
import net.aechronis.logger.utils.placeholders
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class InventoryChange(
    private val database: Database,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val table = database.inventoryChangeTableName
    private val columns =
        "id, ts, player_uuid, player_name, slot, action, item_old, item_new, source, origin, rolled_back"
    private val pendingWrites = ConcurrentHashMap.newKeySet<CompletableFuture<Void>>()

    fun insertAsync(change: InventoryChange): CompletableFuture<Void> {
        val future =
            CompletableFuture.runAsync({
                database.dataSource.connection.use { connection ->
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO "$table"
                                (ts, player_uuid, player_name, slot, action, item_old, item_new, source, origin)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, change.timestamp)
                            statement.setString(2, change.playerUuid.toString())
                            statement.setString(3, change.playerName)
                            statement.setInt(4, change.slot)
                            statement.setString(5, change.action.value)
                            statement.setBytes(6, ItemCodec.encodeItem(change.oldItem))
                            statement.setBytes(7, ItemCodec.encodeItem(change.newItem))
                            statement.setString(8, change.source)
                            statement.setString(9, change.origin)
                            statement.executeUpdate()
                        }
                }
            }, executor)
        pendingWrites += future
        future.whenComplete { _, _ -> pendingWrites -= future }
        return future
    }

    fun flushAsync(): CompletableFuture<Void> = CompletableFuture.allOf(*pendingWrites.toTypedArray())

    fun searchForOperationAsync(
        params: LookupParams,
        targetTs: Long,
        actions: Set<InventoryChangeAction>?,
        rolledBack: Boolean,
        limit: Int,
    ): CompletableFuture<List<InventoryChange>> =
        flushAsync().thenApplyAsync({
            val sql = StringBuilder("SELECT $columns FROM \"$table\" WHERE ts >= ? AND rolled_back = ?")
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
            val rows = mutableListOf<InventoryChange>()
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
                                val keys =
                                    setOf(
                                        change.oldItem
                                            .material()
                                            .key()
                                            .asString(),
                                        change.newItem
                                            .material()
                                            .key()
                                            .asString(),
                                    )
                                if (
                                    (params.include.isEmpty() || params.include.any(keys::contains)) &&
                                    params.exclude.none(keys::contains)
                                ) {
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

    private fun mapRow(results: ResultSet): InventoryChange =
        InventoryChange(
            id = results.getLong("id"),
            timestamp = results.getLong("ts"),
            playerUuid = UUID.fromString(results.getString("player_uuid")),
            playerName = results.getString("player_name"),
            slot = results.getInt("slot"),
            action = InventoryChangeAction.fromValue(results.getString("action")),
            oldItem = ItemCodec.decodeItem(results.getBytes("item_old")),
            newItem = ItemCodec.decodeItem(results.getBytes("item_new")),
            source = results.getString("source"),
            origin = results.getString("origin"),
            rolledBack = results.getBoolean("rolled_back"),
        )

    override fun close() {
        executor.close()
    }
}
