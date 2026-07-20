package net.aechronis.logger

data class LoggerConfig(
    val databasePath: String = "logger.db",
    val poolSize: Int = 4,
    val tableName: String = "block_log",
    val featureTableName: String = "feature_log",
    val storageTableName: String = "storage_change",
    val limit: Int = 9999999,
    val inventorySnapshotTableName: String = "inventory_snapshot",
)
