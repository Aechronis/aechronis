package net.aechronis.logger

data class LoggerConfig(
    val databasePath: String = "logger/logger.db",
    val poolSize: Int = 4,
    val tableName: String = "block_log",
    val featureTableName: String = "feature_log",
    val storageTableName: String = "storage_change",
    val limit: Int = 9999999,
    val inventorySnapshotTableName: String = "inventory_snapshot",
    val rollbackSafetyDefault: Boolean = true,
    val defaultRollbackRadius: Int = 10,
    val rollbackBatchSize: Int = 500,
    val inventoryChangeTableName: String = "inventory_change",
    val entityChangeTableName: String = "entity_change",
)
