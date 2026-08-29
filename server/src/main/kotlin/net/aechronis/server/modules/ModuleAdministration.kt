package net.aechronis.server.modules

data class ModuleSnapshot(
    val generation: Long,
    val phase: String,
    val modules: List<ModuleStatus>,
)

data class ModuleStatus(
    val id: String,
    val enabled: Boolean,
    val dependencies: Set<String>,
    val dependants: Set<String>,
    val sourceJar: String,
    val reason: String? = null,
)

data class ModuleOperationResult(
    val success: Boolean,
    val message: String,
    val affectedIds: Set<String> = emptySet(),
    val rolledBack: Boolean = false,
)

/** Synchronous administration surface used by the server-owned module command. */
interface ModuleAdministration {
    fun snapshot(): ModuleSnapshot

    fun enable(id: String): ModuleOperationResult

    fun disable(
        id: String,
        cascade: Boolean,
    ): ModuleOperationResult

    fun restart(id: String): ModuleOperationResult

    fun reload(): ModuleOperationResult
}
