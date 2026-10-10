package net.aechronis.logger.repos

import net.aechronis.logger.db.Database
import net.aechronis.logger.objects.FeatureLogEntry
import net.aechronis.logger.params.FeatureLookupParams
import net.aechronis.logger.utils.AsyncWriteGate
import net.aechronis.logger.utils.DataCodec
import net.aechronis.logger.utils.appendLogFilters
import net.aechronis.logger.utils.bindAll
import net.aechronis.logger.utils.chunkBounds
import net.aechronis.logger.utils.getNullableInt
import net.aechronis.logger.utils.placeholders
import net.aechronis.logger.utils.radiusBounds
import net.aechronis.logger.utils.setNullableInt
import net.aechronis.logger.utils.setNullableString
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FeatureLog(
    private val database: Database,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val table = database.featureTableName
    private val writeGate = AsyncWriteGate(executor, "feature log repository")

    private val insertSql =
        """
        INSERT INTO "$table"
            (ts, player_uuid, player_name, source, action, summary, x, y, z, data, origin)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    fun insertAsync(entry: FeatureLogEntry): CompletableFuture<Void> = writeGate.submit { insert(entry) }

    private fun insert(entry: FeatureLogEntry) {
        database.dataSource.connection.use { conn ->
            conn.prepareStatement(insertSql).use { ps ->
                ps.setLong(1, entry.timestamp)
                ps.setNullableString(2, entry.playerUuid?.toString())
                ps.setNullableString(3, entry.playerName)
                ps.setString(4, entry.source)
                ps.setString(5, entry.action)
                ps.setString(6, entry.summary)
                ps.setNullableInt(7, entry.x)
                ps.setNullableInt(8, entry.y)
                ps.setNullableInt(9, entry.z)
                ps.setString(10, DataCodec.encode(entry.data))
                ps.setString(11, entry.origin)
                ps.executeUpdate()
            }
        }
    }

    fun searchAsync(
        params: FeatureLookupParams,
        centerX: Int,
        centerY: Int,
        centerZ: Int,
        limit: Int = 200,
    ): CompletableFuture<List<FeatureLogEntry>> =
        CompletableFuture.supplyAsync({ search(params, centerX, centerY, centerZ, limit) }, executor)

    private fun search(
        params: FeatureLookupParams,
        centerX: Int,
        centerY: Int,
        centerZ: Int,
        limit: Int,
    ): List<FeatureLogEntry> {
        val sql =
            StringBuilder(
                "SELECT ts, player_uuid, player_name, source, action, summary, x, y, z, data, origin " +
                    "FROM \"$table\" WHERE LOWER(source) = ?",
            )
        val args = mutableListOf<Any>(params.source.lowercase())

        sql.appendLogFilters(args, params.users, origin = params.origin, since = params.since, until = params.until)
        params.radius?.let { r ->
            radiusBounds(centerX, centerY, centerZ, r).appendSql(sql, args, requireCoordinates = true)
        }
        params.chunkRadius?.let { cr ->
            chunkBounds(centerX, centerZ, cr).appendSql(sql, args, requireCoordinates = true)
        }
        if (params.actions.isNotEmpty()) {
            sql.append(" AND LOWER(action) IN (${placeholders(params.actions.size)})")
            params.actions.forEach { args += it.lowercase() }
        }
        sql.append(" ORDER BY ts DESC LIMIT ?")
        args += limit

        val out = mutableListOf<FeatureLogEntry>()
        database.dataSource.connection.use { conn ->
            conn.prepareStatement(sql.toString()).use { ps ->
                ps.bindAll(args)
                ps.executeQuery().use { rs ->
                    while (rs.next()) out += mapRow(rs)
                }
            }
        }
        return out
    }

    private fun mapRow(rs: ResultSet): FeatureLogEntry =
        FeatureLogEntry(
            timestamp = rs.getLong("ts"),
            playerUuid = rs.getString("player_uuid")?.let(UUID::fromString),
            playerName = rs.getString("player_name"),
            source = rs.getString("source"),
            action = rs.getString("action"),
            summary = rs.getString("summary"),
            x = rs.getNullableInt("x"),
            y = rs.getNullableInt("y"),
            z = rs.getNullableInt("z"),
            data = DataCodec.decode(rs.getString("data")),
            origin = rs.getString("origin"),
        )

    override fun close() {
        writeGate.close()
    }
}
