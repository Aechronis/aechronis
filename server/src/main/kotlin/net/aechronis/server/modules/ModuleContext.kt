package net.aechronis.server.modules

import net.aechronis.server.Server
import net.aechronis.server.ServerShutdown
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.Event
import net.minestom.server.instance.InstanceContainer
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

class ModuleContext(
    saveCoreWorld: () -> Unit = {},
) {
    private val saveCoreWorldCallback = saveCoreWorld
    private val transientState = ConcurrentHashMap<String, ByteArray>()

    val instance: InstanceContainer
        get() = Server.instance

    val spawnPoint: Pos
        get() = Server.spawnPoint

    /** Registers a generation-owned listener that is detached and allowed to finish on unload. */
    fun <E : Event> addListener(
        eventType: Class<E>,
        listener: Consumer<E>,
    ) = ModuleRuntime.addListener(eventType, listener)

    /** Defers lifecycle teardown so a module event callback never waits for itself to quiesce. */
    fun shutdownServer() {
        MinecraftServer.getSchedulerManager().scheduleNextTick(ServerShutdown::shutdown)
    }

    /**
     * Publishes a classloader-neutral snapshot for the next module generation. Payloads are copied
     * on both write and read so the core never retains module-owned objects or mutable arrays.
     * Values intentionally remain available for rollback generations to read again.
     */
    fun publishTransientState(
        key: String,
        payload: ByteArray,
    ) {
        require(key.isNotBlank()) { "Transient-state keys cannot be blank" }
        transientState[key] = payload.copyOf()
    }

    fun peekTransientState(key: String): ByteArray? = transientState[key]?.copyOf()

    fun clearTransientState(key: String) {
        transientState.remove(key)
    }

    internal fun saveCoreWorld() = saveCoreWorldCallback()
}
