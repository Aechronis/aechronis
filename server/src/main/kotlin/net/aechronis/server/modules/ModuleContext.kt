package net.aechronis.server.modules

import net.aechronis.server.Server
import net.aechronis.server.ServerShutdown
import net.aechronis.server.network.ServerNetwork
import net.aechronis.server.network.ServerTransport
import net.aechronis.server.resourcepack.EmbeddedResourcePack
import net.aechronis.server.resourcepack.ModuleResourcePacks
import net.aechronis.server.resourcepack.ResourcePackServer
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
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
    private val liveExecutionAvailable: () -> Boolean = { true },
    private val network: ServerNetwork? = null,
) {
    internal var administration: ModuleAdministration? = null

    fun moduleSnapshot(): ModuleSnapshot = administration?.snapshot() ?: ModuleSnapshot(0, "unavailable", emptyList())

    private val saveCoreWorldCallback = saveCoreWorld
    private val transientState = ConcurrentHashMap<String, ByteArray>()
    private var tickPause: ModuleTickPause? = null

    val instance: InstanceContainer
        get() = Server.instance

    val spawnPoint: Pos
        get() = Server.spawnPoint

    /** Only one transport generation can own the public listener. Close this registration on unload. */
    fun installServerTransport(transport: ServerTransport): AutoCloseable =
        checkNotNull(network) { "The server network is unavailable" }.install(transport)

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

    private var startingModules: Set<String> = emptySet()

    fun isModuleStarting(id: String): Boolean = id in startingModules

    internal fun <T> withStartingModules(
        ids: Set<String>,
        action: () -> T,
    ): T {
        val previous = startingModules
        startingModules = ids
        return try {
            action()
        } finally {
            startingModules = previous
        }
    }

    /** Read the currently published assets, including asset-only updates to a surviving module. */
    fun readResourcePackAsset(
        moduleId: String,
        path: String,
    ): ByteArray? = resourcePackServer?.readAsset(moduleId, path)

    /** Unchanged content reuses its prepared archive before extraction or ZIP creation. */
    internal fun prepareResourcePacks(
        artifact: ModuleArtifact,
        module: AechronisModule,
    ): ModuleResourcePacks? {
        if (artifact.resourcePackFingerprint == null && module.externalResourcePacks.isEmpty()) return null
        var staging: Path? = null
        return try {
            checkNotNull(resourcePackServer) { "Resource-pack server is unavailable" }
                .prepare(module.id, artifact.resourcePackFingerprint, module.externalResourcePacks) {
                    if (artifact.resourcePackFingerprint == null) {
                        null
                    } else {
                        val target =
                            resourcePackDirectory?.let { root ->
                                Files.createDirectories(root)
                                Files.createTempDirectory(root, ".module-${module.id}-")
                            } ?: artifact.directory.resolve("pack")
                        staging = target
                        EmbeddedResourcePack.install(target, artifact.jar)
                    }
                }
        } finally {
            staging?.let(::deleteModuleTree)
        }
    }

    internal fun publishResourcePacks(
        packs: List<ModuleResourcePacks>,
        updateCachedAssets: () -> Unit = {},
    ): Boolean =
        resourcePackServer?.publish(packs, updateCachedAssets) ?: run {
            updateCachedAssets()
            false
        }

    /** Close the registration during shutdown. Provider work participates in module quiescence. */
    fun registerPlayerResourcePack(
        id: String,
        provider: (Player) -> Map<String, ByteArray>?,
    ): AutoCloseable {
        val scope = ModuleRuntime.captureScope()
        return checkNotNull(resourcePackServer) { "Resource-pack server is unavailable" }
            .registerPlayerResourcePack(id) { player ->
                if (scope == null) provider(player) else scope.dispatchCallback(null) { provider(player) }
            }
    }

    /** Queue only the player's overlay layers after their skin becomes available or changes. */
    fun refreshPlayerResourcePacks(player: Player) {
        resourcePackServer?.refreshPlayerResourcePacks(player)
    }

    internal fun sendResourcePacksToOnlinePlayers() {
        val server = resourcePackServer ?: return
        MinecraftServer.getConnectionManager().onlinePlayers.forEach(server::sendResourcePacksAsync)
    }

    /** Park the tick at a scheduler boundary, after the previous entity tick has finished. */
    internal fun pauseGameplay(): AutoCloseable {
        check(tickPause == null) { "Gameplay is already paused" }
        val executor = liveExecutor?.takeIf { liveExecutionAvailable() } ?: return AutoCloseable {}
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

    /**
     * Captures live state at a global tick boundary, including while reload has parked gameplay.
     * Call only from a lifecycle hook, never a player/event callback: waiting there would block the
     * tick that must execute this action. Return immutable snapshots or queued save futures, and
     * wait for disk I/O on the lifecycle worker after this method returns.
     * Before the game starts or after it stops, lifecycle cleanup captures directly because no
     * scheduler is available. An existing gameplay pause always owns live execution until released.
     */
    fun <T> captureLive(capture: () -> T): T {
        val executor = tickPause ?: liveExecutor?.takeIf { liveExecutionAvailable() }
        return if (executor == null) {
            capture()
        } else {
            CompletableFuture.supplyAsync(capture, executor).join()
        }
    }

    internal fun runLive(action: () -> Unit) = captureLive(action)

    internal fun saveCoreWorld() = saveCoreWorldCallback()
}
