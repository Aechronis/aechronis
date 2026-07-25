package net.aechronis.logger.repos

import net.aechronis.logger.Logger
import net.aechronis.logger.db.Database
import net.aechronis.logger.objects.BlockAction
import net.aechronis.logger.objects.BlockLogEntry
import net.aechronis.logger.params.LookupParams
import net.aechronis.logger.utils.bindAll
import net.aechronis.logger.utils.placeholders
import net.aechronis.logger.utils.setNullableBytes
import net.aechronis.logger.utils.setNullableString
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BlockLog(
    private val database: Database,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val table = database.tableName

    private val selectColumns =
        "id, ts, player_uuid, player_name, x, y, z, block_old, block_new, action, " +
            "instance_uuid, block_old_state, block_new_state, block_old_nbt, block_new_nbt, source, origin, rolled_back"
    private val pendingWrites = ConcurrentHashMap.newKeySet<CompletableFuture<Void>>()

    private val insertSql =
        """
        INSERT INTO "$table"
            (ts, player_uuid, player_name, x, y, z, block_old, block_new, action,
             instance_uuid, block_old_state, block_new_state, block_old_nbt, block_new_nbt, source, origin)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    private val lookupSql =
        """
        SELECT $selectColumns
        FROM "$table"
        WHERE x = ? AND y = ? AND z = ?
        ORDER BY ts DESC, id DESC
        LIMIT ?
        """.trimIndent()

    fun insertAsync(entry: BlockLogEntry): CompletableFuture<Void> {
        val future = CompletableFuture.runAsync({ insert(entry) }, executor)
        return trackWrite(future)
    }

    fun insertAllAsync(entries: List<BlockLogEntry>): CompletableFuture<Void> {
        if (entries.isEmpty()) return CompletableFuture.completedFuture(null)
        val future =
            CompletableFuture.runAsync({
                database.dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        connection.prepareStatement(insertSql).use { statement ->
                            entries.forEach { entry ->
                                bindInsert(statement, entry)
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
        return trackWrite(future)
    }

    fun flushAsync(): CompletableFuture<Void> = CompletableFuture.allOf(*pendingWrites.toTypedArray())

    private fun insert(entry: BlockLogEntry) {
        database.dataSource.connection.use { conn ->
            conn.prepareStatement(insertSql).use { ps ->
                bindInsert(ps, entry)
                ps.executeUpdate()
            }
        }
    }

    private fun bindInsert(
        statement: PreparedStatement,
        entry: BlockLogEntry,
    ) {
        statement.setLong(1, entry.timestamp)
        statement.setString(2, entry.playerUuid.toString())
        statement.setString(3, entry.playerName)
        statement.setInt(4, entry.x)
        statement.setInt(5, entry.y)
        statement.setInt(6, entry.z)
        statement.setString(7, entry.blockOld)
        statement.setString(8, entry.blockNew)
        statement.setByte(9, entry.action.id)
        statement.setNullableString(10, entry.instanceUuid?.toString())
        statement.setNullableString(11, entry.blockOldState)
        statement.setNullableString(12, entry.blockNewState)
        statement.setNullableBytes(13, entry.blockOldNbt)
        statement.setNullableBytes(14, entry.blockNewNbt)
        statement.setString(15, entry.source)
        statement.setString(16, entry.origin)
    }

    private fun trackWrite(future: CompletableFuture<Void>): CompletableFuture<Void> {
        pendingWrites += future
        future.whenComplete { _, _ -> pendingWrites -= future }
        return future
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

    fun searchAsync(
        params: LookupParams,
        centerX: Int,
        centerY: Int,
        centerZ: Int,
        limit: Int = 200,
    ): CompletableFuture<List<BlockLogEntry>> =
        CompletableFuture.supplyAsync({ search(params, centerX, centerY, centerZ, limit) }, executor)

    private fun search(
        params: LookupParams,
        centerX: Int,
        centerY: Int,
        centerZ: Int,
        limit: Int,
    ): List<BlockLogEntry> {
        val (sql, args) = buildWhereClause(params, centerX, centerY, centerZ)
        sql.append(" ORDER BY ts DESC, id DESC LIMIT ?")
        args += limit
        return runQuery(sql.toString(), args)
    }

    fun searchForRollbackAsync(
        params: LookupParams,
        targetTs: Long,
        instanceUuid: UUID,
        centerX: Int,
        centerY: Int,
        centerZ: Int,
        limit: Int = Logger.config.rollbackMaxChanges,
    ): CompletableFuture<List<BlockLogEntry>> =
        searchForOperationAsync(params, targetTs, instanceUuid, centerX, centerY, centerZ, rolledBack = false, limit = limit)

    fun searchForRestoreAsync(
        params: LookupParams,
        targetTs: Long,
        instanceUuid: UUID,
        centerX: Int,
        centerY: Int,
        centerZ: Int,
        limit: Int = Logger.config.rollbackMaxChanges,
    ): CompletableFuture<List<BlockLogEntry>> =
        searchForOperationAsync(params, targetTs, instanceUuid, centerX, centerY, centerZ, rolledBack = true, limit = limit)

    private fun searchForOperationAsync(
        params: LookupParams,
        targetTs: Long,
        instanceUuid: UUID,
        centerX: Int,
        centerY: Int,
        centerZ: Int,
        rolledBack: Boolean,
        limit: Int,
    ): CompletableFuture<List<BlockLogEntry>> =
        flushAsync().thenApplyAsync({
            val effectiveParams = params.copy(since = targetTs)
            val (sql, args) = buildWhereClause(effectiveParams, centerX, centerY, centerZ)
            sql.append(" AND instance_uuid = ? AND rolled_back = ? AND action IN (?, ?)")
            args += instanceUuid.toString()
            args += rolledBack
            args += BlockAction.BREAK.id
            args += BlockAction.PLACE.id
            sql.append(if (rolledBack) " ORDER BY ts ASC, id ASC LIMIT ?" else " ORDER BY ts DESC, id DESC LIMIT ?")
            args += if (limit == Int.MAX_VALUE) limit else limit + 1
            runQuery(sql.toString(), args)
        }, executor)

    private fun buildWhereClause(
        params: LookupParams,
        centerX: Int,
        centerY: Int,
        centerZ: Int,
    ): Pair<StringBuilder, MutableList<Any>> {
        val sql = StringBuilder("SELECT $selectColumns FROM \"$table\" WHERE 1=1")
        val args = mutableListOf<Any>()

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
        params.since?.let {
            sql.append(" AND ts >= ?")
            args += it
        }
        params.until?.let {
            sql.append(" AND ts <= ?")
            args += it
        }
        params.radius?.let { r ->
            sql.append(" AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?")
            args += centerX - r
            args += centerX + r
            args += centerY - r
            args += centerY + r
            args += centerZ - r
            args += centerZ + r
        }
        params.chunkRadius?.let { cr ->
            val expand = cr - 1
            val minX = ((centerX shr 4) - expand) shl 4
            val maxX = (((centerX shr 4) + expand) shl 4) + 15
            val minZ = ((centerZ shr 4) - expand) shl 4
            val maxZ = (((centerZ shr 4) + expand) shl 4) + 15
            sql.append(" AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?")
            args += minX
            args += maxX
            args += minZ
            args += maxZ
        }
        params.actions?.let { acts ->
            sql.append(" AND action IN (${placeholders(acts.size)})")
            acts.forEach { args += it.id }
        }
        if (params.include.isNotEmpty()) {
            val ph = placeholders(params.include.size)
            sql.append(" AND (block_old IN ($ph) OR block_new IN ($ph))")
            params.include.forEach { args += it }
            params.include.forEach { args += it }
        }
        if (params.exclude.isNotEmpty()) {
            val ph = placeholders(params.exclude.size)
            sql.append(" AND block_old NOT IN ($ph) AND block_new NOT IN ($ph)")
            params.exclude.forEach { args += it }
            params.exclude.forEach { args += it }
        }
        return sql to args
    }

    private fun runQuery(
        sql: String,
        args: List<Any>,
    ): List<BlockLogEntry> {
        val out = mutableListOf<BlockLogEntry>()
        database.dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.bindAll(args)
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
            source = rs.getString("source"),
            origin = rs.getString("origin"),
            instanceUuid = rs.getString("instance_uuid")?.let(UUID::fromString),
            blockOldState = rs.getString("block_old_state"),
            blockNewState = rs.getString("block_new_state"),
            blockOldNbt = rs.getBytes("block_old_nbt"),
            blockNewNbt = rs.getBytes("block_new_nbt"),
            rolledBack = rs.getBoolean("rolled_back"),
        )

    override fun close() {
        executor.close()
    }
}
