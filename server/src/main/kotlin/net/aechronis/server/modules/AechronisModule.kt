package net.aechronis.server.modules

import java.util.concurrent.CompletableFuture

interface AechronisModule {
    val id: String

    val dependencies: Set<String>
        get() = emptySet()

    fun initialize(context: ModuleContext) = Unit

    /**
     * Stops accepting mutable background work and waits for work already in flight to finish.
     * This hook is called in reverse dependency order before state and world persistence, and must
     * be idempotent because teardown invokes it defensively as well.
     */
    fun prepareForShutdown(context: ModuleContext) = Unit

    fun saveState(context: ModuleContext) = Unit

    fun saveCheckpoint(context: ModuleContext): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    fun shutdown(context: ModuleContext) = Unit
}
