package net.aechronis.logger.db

import net.aechronis.logger.objects.FeatureLogEntry
import net.aechronis.logger.params.FeatureLookupParams
import net.aechronis.logger.utils.DataCodec
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FeatureLogRepository(
    private val database: Database,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val table = database.featureTableName

    private val insertSql =
        """
        INSERT INTO `$table`
            (ts, player_uuid, player_name, source, action, summary, x, y, z, data)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    fun insertAsync(entry: FeatureLogEntry): CompletableFuture<Void> = CompletableFuture.runAsync({ insert(entry) }, executor)

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
                "SELECT ts, player_uuid, player_name, source, action, summary, x, y, z, data " +
                    "FROM `$table` WHERE LOWER(source) = ?",
            )
        val args = mutableListOf<Any>(params.source.lowercase())

        if (params.users.isNotEmpty()) {
            sql.append(" AND LOWER(player_name) IN (${placeholders(params.users.size)})")
            params.users.forEach { args += it.lowercase() }
        }
        params.since?.let {
            sql.append(" AND ts >= ?")
            args += it
        }
        params.radius?.let { r ->
            sql.append(
                " AND x IS NOT NULL AND y IS NOT NULL AND z IS NOT NULL" +
                    " AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?",
            )
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
            sql.append(" AND x IS NOT NULL AND z IS NOT NULL AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?")
            args += minX
            args += maxX
            args += minZ
            args += maxZ
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
        )

    override fun close() {
        executor.shutdown()
    }
}
