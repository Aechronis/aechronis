package net.aechronis.server.modules

import net.aechronis.server.Server
import net.aechronis.server.ServerShutdown
import net.aechronis.server.resourcepack.EmbeddedResourcePack
import net.aechronis.server.resourcepack.ModuleResourcePacks
import net.aechronis.server.resourcepack.ResourcePackRegistration
import net.aechronis.server.resourcepack.ResourcePackServer
import net.kyori.adventure.resource.ResourcePackInfo
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.Event
import net.minestom.server.instance.InstanceContainer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.function.Consumer

class ModuleContext(
    saveCoreWorld: () -> Unit = {},
    private val resourcePackDirectory: Path? = null,
    private val resourcePackServer: ResourcePackServer? = null,
    private val liveExecutor: Executor? = null,
) {
    private val saveCoreWorldCallback = saveCoreWorld
    private val transientState = ConcurrentHashMap<String, ByteArray>()
    private var tickPause: ModuleTickPause? = null

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

    /** Extraction, ZIP creation and hashing happen before any running module is stopped. */
    internal fun prepareResourcePacks(
        artifact: ModuleArtifact,
        module: AechronisModule,
    ): ModuleResourcePacks? {
        val staging =
            resourcePackDirectory?.let { root ->
                Files.createDirectories(root)
                Files.createTempDirectory(root, ".module-${module.id}-")
            } ?: artifact.directory.resolve("pack")
        return try {
            val installed = EmbeddedResourcePack.installIfPresent(staging, module.javaClass)
            if (installed == null && module.externalResourcePacks.isEmpty()) {
                null
            } else {
                checkNotNull(resourcePackServer) { "Resource-pack server is unavailable" }
                    .prepare(module.id, installed, module.externalResourcePacks)
            }
        } finally {
            // The prepared archive owns a separate file; extracted assets are no longer needed.
            deleteModuleTree(staging)
        }
    }

    internal fun installResourcePacks(packs: ModuleResourcePacks): ResourcePackRegistration =
        checkNotNull(resourcePackServer).installPrepared(packs)

    internal fun sendResourcePacksToOnlinePlayers() {
        val server = resourcePackServer ?: return
        MinecraftServer.getConnectionManager().onlinePlayers.forEach(server::sendResourcePacks)
    }

    internal fun removeResourcePacksFromOnlinePlayers(moduleIds: Set<String>) {
        val server = resourcePackServer ?: return
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            val packs = server.resourcePackInfos(player.playerConnection.serverAddress, moduleIds)
            if (packs.isNotEmpty()) player.removeResourcePacks(packs.map(ResourcePackInfo::id))
        }
    }

    /** Park the tick at a scheduler boundary, after the previous entity tick has finished. */
    internal fun pauseGameplay(): AutoCloseable {
        check(tickPause == null) { "Gameplay is already paused" }
        val executor = liveExecutor ?: return AutoCloseable {}
        val pause = ModuleTickPause(executor)
        tickPause = pause
        val startedAt = System.nanoTime()
        return AutoCloseable {
            try {
                pause.close()
            } finally {
                tickPause = null
                println("[Modules] Gameplay paused for ${(System.nanoTime() - startedAt) / 1_000_000}ms")
            }
        }
    }

    internal fun runLive(action: () -> Unit) {
        val executor = tickPause ?: liveExecutor
        if (executor == null) {
            action()
        } else {
            CompletableFuture.runAsync(action, executor).join()
        }
    }

    internal fun saveCoreWorld() = saveCoreWorldCallback()
}
