package net.aechronis.server.resourcepack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventListener
import net.minestom.server.event.player.PlayerDisconnectEvent
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ResourcePackServer private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
    private val publicBaseUri: URI?,
) : AutoCloseable {
    val port: Int
        get() = server.address.port

    private val activePacks = linkedMapOf<String, ModuleResourcePacks>()
    private val playerProviders = linkedMapOf<String, PlayerPackProvider>()
    private val playerRequests = hashMapOf<UUID, PlayerPackRequest>()
    private var disconnectListener: EventListener<PlayerDisconnectEvent>? = null
    private var packRevision = 0L
    private var serverStopped = false
    private var closed = false

    internal fun prepare(
        id: String,
        directory: Path?,
        externalPacks: List<ResourcePackInfo>,
    ): ModuleResourcePacks {
        require(id.matches(RESOURCE_PACK_ID_PATTERN)) { "Invalid resource-pack ID: '$id'" }
        require(directory != null || externalPacks.isNotEmpty()) { "Module '$id' does not provide any resource packs" }
        val hostedPack =
            directory?.let {
                ServedResourcePack(
                    id = id,
                    uuid = UUID.nameUUIDFromBytes("aechronis:resource-pack:$id".toByteArray(StandardCharsets.UTF_8)),
                    archive = ResourcePackArchive.create(it),
                )
            }
        val packs =
            ModuleResourcePacks(
                moduleId = id,
                hostedPack = hostedPack,
                externalPacks = externalPacks.toList(),
            )
        return packs
    }

    @Synchronized
    internal fun installPrepared(packs: ModuleResourcePacks): ResourcePackRegistration {
        check(!closed) { "Resource-pack server is closed" }
        val id = packs.moduleId
        check(id !in activePacks) { "Resource packs for module '$id' are already active" }
        val hostedPack = packs.hostedPack
        val externalPacks = packs.externalPacks
        activePacks[id] = packs
        packRevision += 1
        hostedPack?.let { println("[ResourcePack] serving ${it.id} (${it.archive.hash})") }
        if (externalPacks.isNotEmpty()) {
            println("[ResourcePack] registered ${externalPacks.size} external pack(s) for $id")
        }
        return ResourcePackRegistration(this, packs)
    }

    @Synchronized
    fun resourcePackInfos(
        serverAddress: String?,
        moduleIds: Set<String>? = null,
    ): List<ResourcePackInfo> {
        check(!closed) { "Resource-pack server is closed" }
        return buildList {
            // External packs are base layers. Keep every one of them below all hosted module
            // packs so module assets consistently override their external dependencies.
            activePacks.values.filter { moduleIds == null || it.moduleId in moduleIds }.forEach { addAll(it.externalPacks) }
            activePacks.values
                .filter { moduleIds == null || it.moduleId in moduleIds }
                .mapNotNull(
                    ModuleResourcePacks::hostedPack,
                ).forEach { pack ->
                    add(resourcePackInfo(pack, serverAddress))
                }
        }
    }

    /** Providers run on configuration or HTTP workers, never while holding the registry lock. */
    @Synchronized
    fun registerPlayerResourcePack(
        id: String,
        provider: (Player) -> Map<String, ByteArray>?,
    ): AutoCloseable {
        check(!closed) { "Resource-pack server is closed" }
        require(id.matches(RESOURCE_PACK_ID_PATTERN)) { "Invalid resource-pack ID: '$id'" }
        check(id !in playerProviders) { "Player resource-pack provider '$id' is already active" }
        if (disconnectListener == null) {
            disconnectListener = EventListener.of(PlayerDisconnectEvent::class.java) { event -> releasePlayer(event.player.uuid) }
            MinecraftServer.getGlobalEventHandler().addListener(checkNotNull(disconnectListener))
        }
        val registration = PlayerPackProvider(id, provider)
        playerProviders[id] = registration
        return AutoCloseable { unregisterPlayerProvider(registration) }
    }

    /** Used by AsyncPlayerConfigurationEvent, where building a personalized pack may block. */
    fun sendResourcePacks(player: Player) {
        val request = beginPlayerRequest(player, includeBase = true) ?: return
        prepareAndSend(player, request)
    }

    /** Refreshing a skin only replaces personalized layers; base archives stay installed. */
    fun refreshPlayerResourcePacks(player: Player) = enqueuePlayerPacks(player, includeBase = false)

    internal fun sendResourcePacksAsync(player: Player) = enqueuePlayerPacks(player, includeBase = true)

    private fun enqueuePlayerPacks(
        player: Player,
        includeBase: Boolean,
    ) {
        val request = beginPlayerRequest(player, includeBase) ?: return
        runCatching {
            executor.execute {
                runCatching { prepareAndSend(player, request) }
                    .onFailure { println("[ResourcePack] player pack refresh failed: ${it.message}") }
            }
        }.onFailure { if (!synchronized(this) { closed }) throw it }
    }

    @Synchronized
    private fun beginPlayerRequest(
        player: Player,
        includeBase: Boolean,
    ): PlayerPackRequest? {
        if (closed || !player.isOnline) return null
        // A skin refresh may supersede a hot-reload worker, but it must retain
        // that worker's obligation to publish the new shared stack as well.
        val request = PlayerPackRequest(includeBase || playerRequests[player.uuid]?.includeBase == true)
        playerRequests[player.uuid] = request
        return request
    }

    private fun prepareAndSend(
        player: Player,
        request: PlayerPackRequest,
    ) {
        val (revision, providers) =
            synchronized(this) {
                if (closed || playerRequests[player.uuid] !== request || !player.isOnline) return
                packRevision to playerProviders.values.toList()
            }
        val prepared = mutableListOf<Pair<PlayerPackProvider, ResourcePackArchive?>>()
        val adopted = mutableSetOf<ResourcePackArchive>()
        try {
            for (provider in providers) {
                val callback =
                    synchronized(this) {
                        if (closed || playerRequests[player.uuid] !== request || !player.isOnline) return
                        provider.callback
                    } ?: continue
                val archive =
                    runCatching { callback(player)?.let(ResourcePackArchive::create) }
                        .onFailure { println("[ResourcePack] player provider '${provider.id}' failed: ${it.message}") }
                        .getOrNull()
                prepared += provider to archive
            }
            synchronized(this) {
                if (closed || playerRequests[player.uuid] !== request || !player.isOnline) return
                if (revision != packRevision) {
                    // The atlas may have changed while the provider built its
                    // image. Rebuild against the new base instead of losing the
                    // player's one-shot skin refresh or publishing stale pixels.
                    enqueuePlayerPacks(player, request.includeBase)
                    return
                }
                val overlays = mutableListOf<ResourcePackInfo>()
                val removed = mutableListOf<UUID>()
                for ((provider, archive) in prepared) {
                    if (playerProviders[provider.id] !== provider) continue
                    val previous = provider.packs[player.uuid]
                    if (archive == null) {
                        provider.packs.remove(player.uuid)
                        previous?.let {
                            removed += it.uuid
                            it.retire()
                        }
                        continue
                    }
                    val pack =
                        if (previous?.archive?.hash == archive.hash) {
                            previous
                        } else {
                            val uuid =
                                UUID.nameUUIDFromBytes(
                                    "aechronis:player-resource-pack:${provider.id}:${player.uuid}".toByteArray(StandardCharsets.UTF_8),
                                )
                            ServedResourcePack("player-$uuid", uuid, archive).also {
                                adopted += archive
                                provider.packs[player.uuid] = it
                                previous?.retire()
                            }
                        }
                    overlays += resourcePackInfo(pack, player.playerConnection.serverAddress)
                }
                // Sending under the lock makes the request token check and publication atomic.
                // A newer skin request cannot be followed by a stale worker's packet.
                if (removed.isNotEmpty()) player.removeResourcePacks(removed)
                val base = if (request.includeBase) resourcePackInfos(player.playerConnection.serverAddress) else emptyList()
                sendResourcePacks(player, base + overlays)
                playerRequests.remove(player.uuid, request)
            }
        } finally {
            synchronized(this) {
                for ((provider, archive) in prepared) {
                    if (archive != null && archive !in adopted) archive.close()
                }
                if (playerProviders.isEmpty()) playerRequests.remove(player.uuid, request)
            }
        }
    }

    @Synchronized
    private fun releasePlayer(uuid: UUID) {
        playerRequests.remove(uuid)
        for (provider in playerProviders.values) provider.packs.remove(uuid)?.retire()
    }

    @Synchronized
    private fun unregisterPlayerProvider(provider: PlayerPackProvider) {
        if (playerProviders[provider.id] !== provider) return
        playerProviders.remove(provider.id)
        provider.callback = null
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            provider.packs[player.uuid]?.let { player.removeResourcePacks(it.uuid) }
        }
        provider.packs.values.forEach(ServedResourcePack::retire)
        provider.packs.clear()
        if (playerProviders.isEmpty()) playerRequests.clear()
    }

    private fun sendResourcePacks(
        player: Player,
        packs: List<ResourcePackInfo>,
    ) {
        if (packs.isEmpty()) return
        player.sendResourcePacks(
            ResourcePackRequest
                .resourcePackRequest()
                .packs(packs)
                .prompt(Component.text("A resource pack is required to play"))
                .required(true)
                .build(),
        )
    }

    private fun resourcePackInfo(
        pack: ServedResourcePack,
        serverAddress: String?,
    ): ResourcePackInfo =
        ResourcePackInfo
            .resourcePackInfo()
            .id(pack.uuid)
            .uri(publicUri(pack, serverAddress))
            .hash(pack.archive.hash)
            .build()

    internal fun publicUri(
        pack: ServedResourcePack,
        serverAddress: String?,
    ): URI {
        publicBaseUri?.let { return it.resolve("${pack.id}/${pack.archive.hash}.zip") }

        val host =
            serverAddress
                ?.substringBefore('\u0000')
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: "127.0.0.1"
        return URI("http", null, host, port, pack.path, null, null)
    }

    @Synchronized
    internal fun uninstall(packs: ModuleResourcePacks) {
        if (activePacks[packs.moduleId] !== packs) return
        activePacks.remove(packs.moduleId)
        packRevision += 1
        packs.hostedPack?.let { pack ->
            pack.retire()
            println("[ResourcePack] stopped serving ${pack.id}")
        }
    }

    override fun close() {
        val packs =
            synchronized(this) {
                if (closed) return
                closed = true
                disconnectListener?.let { MinecraftServer.getGlobalEventHandler().removeListener(it) }
                disconnectListener = null
                val packs =
                    activePacks.values.mapNotNull(ModuleResourcePacks::hostedPack) + playerProviders.values.flatMap { it.packs.values }
                activePacks.clear()
                playerProviders.values.forEach {
                    it.callback = null
                    it.packs.clear()
                }
                playerProviders.clear()
                playerRequests.clear()
                packs
            }
        var failure: Throwable? = null

        fun cleanup(action: () -> Unit) {
            runCatching(action).onFailure { error ->
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }

        if (!serverStopped) {
            cleanup {
                server.stop(0)
                serverStopped = true
            }
        }
        cleanup { shutdownResourcePackExecutor(executor) }
        packs.forEach { cleanup(it::retire) }
        failure?.let { throw it }
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val pack =
                synchronized(this) {
                    (activePacks.values.mapNotNull(ModuleResourcePacks::hostedPack) + playerProviders.values.flatMap { it.packs.values })
                        .firstOrNull { it.path == exchange.requestURI.path }
                }
            if (pack == null || !pack.acquire()) {
                exchange.sendResponseHeaders(404, -1)
                return
            }

            try {
                val method = exchange.requestMethod
                if (method != "GET" && method != "HEAD") {
                    exchange.responseHeaders.set("Allow", "GET, HEAD")
                    exchange.sendResponseHeaders(405, -1)
                    return
                }

                exchange.responseHeaders.set("Content-Type", "application/zip")
                exchange.responseHeaders.set(
                    "Content-Disposition",
                    "attachment; filename=\"aechronis-${pack.id}-resource-pack.zip\"",
                )
                exchange.responseHeaders.set("Cache-Control", "public, max-age=31536000, immutable")
                exchange.responseHeaders.set("ETag", "\"${pack.archive.hash}\"")

                if (method == "HEAD") {
                    exchange.responseHeaders.set("Content-Length", pack.archive.size.toString())
                    exchange.sendResponseHeaders(200, -1)
                    return
                }

                exchange.sendResponseHeaders(200, pack.archive.size)
                Files.newInputStream(pack.archive.path).use { input ->
                    input.transferTo(exchange.responseBody)
                }
            } finally {
                pack.release()
            }
        }
    }

    private class PlayerPackProvider(
        val id: String,
        var callback: ((Player) -> Map<String, ByteArray>?)?,
        val packs: MutableMap<UUID, ServedResourcePack> = hashMapOf(),
    )

    private class PlayerPackRequest(
        val includeBase: Boolean,
    )

    companion object {
        private val RESOURCE_PACK_ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,63}")

        fun start(
            address: InetSocketAddress,
            publicBaseUri: URI? = null,
        ): ResourcePackServer {
            val normalizedPublicBaseUri = publicBaseUri?.normalizePublicBaseUri()
            var server: HttpServer? = null
            var executor: ExecutorService? = null

            return try {
                server = HttpServer.create(address, 0)
                executor = Executors.newVirtualThreadPerTaskExecutor()
                val result = ResourcePackServer(server, executor, normalizedPublicBaseUri)
                server.executor = executor
                server.createContext("/resource-pack/", result::handle)
                server.start()
                result
            } catch (error: Throwable) {
                runCatching { server?.stop(0) }.exceptionOrNull()?.let(error::addSuppressed)
                try {
                    executor?.let(::shutdownResourcePackExecutor)
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                throw error
            }
        }
    }
}

class ResourcePackRegistration internal constructor(
    private val server: ResourcePackServer,
    private val packs: ModuleResourcePacks,
) : AutoCloseable {
    override fun close() = server.uninstall(packs)
}

internal data class ModuleResourcePacks(
    val moduleId: String,
    val hostedPack: ServedResourcePack?,
    val externalPacks: List<ResourcePackInfo>,
)

internal class ServedResourcePack(
    val id: String,
    val uuid: UUID,
    val archive: ResourcePackArchive,
) {
    val path = "/resource-pack/$id/${archive.hash}.zip"

    private var activeRequests = 0
    private var retired = false
    private var archiveClosed = false

    @Synchronized
    fun acquire(): Boolean {
        if (retired) return false
        activeRequests += 1
        return true
    }

    @Synchronized
    fun release() {
        activeRequests -= 1
        closeArchiveIfIdle()
    }

    @Synchronized
    fun retire() {
        retired = true
        closeArchiveIfIdle()
    }

    private fun closeArchiveIfIdle() {
        if (retired && activeRequests == 0 && !archiveClosed) {
            archive.close()
            archiveClosed = true
        }
    }
}

internal fun shutdownResourcePackExecutor(
    executor: ExecutorService,
    gracefulTimeout: Duration = Duration.ofSeconds(5),
    forcedTimeout: Duration = Duration.ofSeconds(2),
) {
    require(!gracefulTimeout.isNegative && !forcedTimeout.isNegative) { "Executor shutdown timeouts cannot be negative" }
    executor.shutdown()
    try {
        if (executor.awaitTermination(gracefulTimeout.toMillis(), TimeUnit.MILLISECONDS)) return
        val abandoned = executor.shutdownNow().size
        check(executor.awaitTermination(forcedTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            "Resource-pack HTTP executor did not terminate after interruption ($abandoned queued task(s) abandoned)"
        }
    } catch (error: InterruptedException) {
        executor.shutdownNow()
        Thread.currentThread().interrupt()
        throw IllegalStateException("Interrupted while stopping the resource-pack HTTP executor", error)
    }
}

private fun URI.normalizePublicBaseUri(): URI {
    require(isAbsolute && (scheme == "http" || scheme == "https") && host != null) {
        "Resource-pack public base URL must be an absolute HTTP(S) URL: $this"
    }
    require(rawQuery == null && rawFragment == null) {
        "Resource-pack public base URL cannot contain a query or fragment: $this"
    }
    return if (path.endsWith('/')) this else URI("$this/")
}

internal data class ResourcePackArchive(
    val path: Path,
    val hash: String,
    val size: Long,
) : AutoCloseable {
    override fun close() {
        Files.deleteIfExists(path)
    }

    companion object {
        fun create(files: Map<String, ByteArray>): ResourcePackArchive {
            require("pack.mcmeta" in files) { "Player resource pack must contain pack.mcmeta" }
            files.keys.forEach(::requirePackEntry)
            val archive = Files.createTempFile("aechronis-player-resource-pack-", ".zip")
            try {
                ZipOutputStream(Files.newOutputStream(archive)).use { output ->
                    for ((name, bytes) in files.toSortedMap()) {
                        output.putNextEntry(ZipEntry(name).apply { time = 0L })
                        output.write(bytes)
                        output.closeEntry()
                    }
                }
                return ResourcePackArchive(archive, sha1(archive), Files.size(archive))
            } catch (exception: Exception) {
                Files.deleteIfExists(archive)
                throw exception
            }
        }

        fun create(directory: Path): ResourcePackArchive {
            val root = directory.toAbsolutePath().normalize()
            require(Files.isDirectory(root)) { "Resource-pack directory does not exist: $root" }
            require(Files.isRegularFile(root.resolve("pack.mcmeta"), LinkOption.NOFOLLOW_LINKS)) {
                "Resource-pack directory does not contain pack.mcmeta: $root"
            }

            val archive = Files.createTempFile("aechronis-resource-pack-", ".zip")
            try {
                ZipOutputStream(Files.newOutputStream(archive)).use { output ->
                    Files.walk(root).use { paths ->
                        paths
                            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                            .sorted(compareBy { root.relativize(it).toString() })
                            .forEach { file ->
                                val entryName = root.relativize(file).joinToString("/") { it.toString() }
                                val entry = ZipEntry(entryName)
                                // Content-identical packs must keep the same hash across extraction and restarts.
                                entry.time = 0L
                                output.putNextEntry(entry)
                                Files.copy(file, output)
                                output.closeEntry()
                            }
                    }
                }

                return ResourcePackArchive(
                    path = archive,
                    hash = sha1(archive),
                    size = Files.size(archive),
                )
            } catch (exception: Exception) {
                Files.deleteIfExists(archive)
                throw exception
            }
        }

        private fun sha1(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-1")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

private fun requirePackEntry(path: String) {
    require(path.isNotEmpty() && '\\' !in path && '\u0000' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
        "Invalid resource-pack entry: '$path'"
    }
}
