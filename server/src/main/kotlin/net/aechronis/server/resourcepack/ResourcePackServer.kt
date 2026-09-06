package net.aechronis.server.resourcepack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player
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

    fun sendResourcePacks(player: Player) {
        sendResourcePacks(player, resourcePackInfos(player.playerConnection.serverAddress))
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
        packs.hostedPack?.let { pack ->
            pack.retire()
            println("[ResourcePack] stopped serving ${pack.id}")
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
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
        activePacks.values.forEach { packs -> packs.hostedPack?.let { cleanup(it::retire) } }
        activePacks.clear()
        failure?.let { throw it }
        closed = true
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val pack =
                synchronized(this) {
                    activePacks.values
                        .asSequence()
                        .mapNotNull(ModuleResourcePacks::hostedPack)
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
