package net.aechronis.logger.repos

import net.aechronis.logger.db.Database
import net.aechronis.logger.objects.StorageChange
import net.aechronis.logger.objects.StorageChangeAction
import net.aechronis.logger.objects.VanillaStorage
import net.aechronis.logger.params.LookupParams
import net.aechronis.logger.utils.AsyncWriteGate
import net.aechronis.logger.utils.BlockBounds
import net.aechronis.logger.utils.ItemCodec
import net.aechronis.logger.utils.LogMetadata
import net.aechronis.logger.utils.appendLogFilters
import net.aechronis.logger.utils.chunkBounds
import net.aechronis.logger.utils.getNullableInt
import net.aechronis.logger.utils.placeholders
import net.aechronis.logger.utils.queryFilteredPages
import net.aechronis.logger.utils.radiusBounds
import net.aechronis.logger.utils.setNullableInt
import net.aechronis.logger.utils.setNullableString
import net.minestom.server.item.ItemStack
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StorageChange(
    private val database: Database,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val table = database.storageTableName
    private val selectColumns =
        "id, ts, player_uuid, player_name, storage_id, action, item_data, amount, slot, source, origin, rolled_back"
    private val writeGate = AsyncWriteGate(executor, "storage change repository")

    private val insertSql =
        """
        INSERT INTO "$table"
            (ts, player_uuid, player_name, storage_id, action, item_data, amount, slot, source, origin)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    fun insertAsync(change: StorageChange): CompletableFuture<Void> = writeGate.submit { insert(change) }

    fun flushAsync(): CompletableFuture<Void> = writeGate.flushAsync()

    fun insertAllAsync(changes: List<StorageChange>): CompletableFuture<Void> {
        if (changes.isEmpty()) return CompletableFuture.completedFuture(null)
        return writeGate.submit {
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
        }
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
            sql.appendLogFilters(args, params.users, params.source, params.origin, until = params.until)
            actions?.let {
                sql.append(" AND action IN (${placeholders(it.size)})")
                it.forEach { action -> args += action.value }
            }
            sql.append(if (rolledBack) " ORDER BY ts ASC, id ASC" else " ORDER BY ts DESC, id DESC")
            val wanted = if (limit == Int.MAX_VALUE) Int.MAX_VALUE else limit + 1
            database.dataSource.queryFilteredPages(sql.toString(), args, wanted, ::mapRow) { change ->
                val key =
                    change.item
                        .material()
                        .key()
                        .asString()
                (params.include.isEmpty() || key in params.include) && key !in params.exclude
            }
        }, executor)

    fun lookupAsync(
        storageId: String,
        limit: Int = 200,
    ): CompletableFuture<List<StorageChange>> {
        require(limit > 0) { "storage lookup limit must be positive" }
        return flushAsync().thenApplyAsync({
            val rows = mutableListOf<StorageChange>()
            database.dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "SELECT $selectColumns FROM \"$table\" WHERE storage_id = ? ORDER BY ts DESC, id DESC LIMIT ?",
                    ).use { statement ->
                        statement.setString(1, storageId)
                        statement.setInt(2, limit)
                        statement.executeQuery().use { results ->
                            while (results.next()) rows += mapRow(results)
                        }
                    }
            }
            rows
        }, executor)
    }

    fun searchAsync(
        params: LookupParams,
        actions: Set<StorageChangeAction>,
        centerX: Int,
        centerY: Int,
        centerZ: Int,
        limit: Int = 200,
    ): CompletableFuture<List<StorageChange>> {
        require(limit > 0) { "storage lookup limit must be positive" }
        return flushAsync().thenApplyAsync({
            val sql = StringBuilder("SELECT $selectColumns FROM \"$table\" WHERE 1=1")
            val args = mutableListOf<Any>()
            sql.appendLogFilters(args, params.users, params.source, params.origin, params.since, params.until)
            sql.append(" AND action IN (${placeholders(actions.size)})")
            actions.forEach { action -> args += action.value }
            sql.append(" ORDER BY ts DESC, id DESC")

            val radius = params.radius?.let { radiusBounds(centerX, centerY, centerZ, it) }
            val chunks = params.chunkRadius?.let { chunkBounds(centerX, centerZ, it) }
            database.dataSource.queryFilteredPages(sql.toString(), args, limit, ::mapRow) { change ->
                matchesLookup(change, params, radius, chunks)
            }
        }, executor)
    }

    private fun matchesLookup(
        change: StorageChange,
        params: LookupParams,
        radius: BlockBounds?,
        chunks: BlockBounds?,
    ): Boolean {
        val itemKey =
            change.item
                .material()
                .key()
                .asString()
        if (params.include.isNotEmpty() && itemKey !in params.include) return false
        if (itemKey in params.exclude) return false

        if (radius == null && chunks == null) return true
        val location = VanillaStorage.parseStorageId(change.storageId) ?: return false
        return (radius == null || radius.contains(location.second, location.third, location.fourth)) &&
            (chunks == null || chunks.contains(location.second, location.third, location.fourth))
    }

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
        writeGate.close()
    }
}
