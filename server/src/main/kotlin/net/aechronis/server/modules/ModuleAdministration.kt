package net.aechronis.server.modules

import java.util.concurrent.CompletableFuture

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

/** Administration operations complete asynchronously; cancelling a view never cancels a reload. */
interface ModuleAdministration {
    fun snapshot(): ModuleSnapshot

    fun enable(id: String): CompletableFuture<ModuleOperationResult>

    fun disable(
        id: String,
        cascade: Boolean,
    ): CompletableFuture<ModuleOperationResult>

    fun restart(id: String): CompletableFuture<ModuleOperationResult>

    fun reload(): CompletableFuture<ModuleOperationResult>
}
