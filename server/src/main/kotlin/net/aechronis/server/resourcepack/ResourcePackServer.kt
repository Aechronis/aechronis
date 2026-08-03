package net.aechronis.server.resourcepack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import net.kyori.adventure.resource.ResourcePackInfo
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ResourcePackServer private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
    private val archive: ResourcePackArchive,
    private val publicBaseUri: URI?,
) : AutoCloseable {
    val port: Int
        get() = server.address.port

    val hash: String
        get() = archive.hash

    private val path = "/resource-pack/${archive.hash}.zip"
    private val closed = AtomicBoolean()

    fun resourcePackInfo(serverAddress: String?): ResourcePackInfo =
        ResourcePackInfo
            .resourcePackInfo()
            .id(RESOURCE_PACK_ID)
            .uri(publicUri(serverAddress))
            .hash(archive.hash)
            .build()

    internal fun publicUri(serverAddress: String?): URI {
        publicBaseUri?.let { return it.resolve("${archive.hash}.zip") }

        val host =
            serverAddress
                ?.substringBefore('\u0000')
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: "127.0.0.1"
        return URI("http", null, host, port, path, null, null)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.stop(0)
        executor.close()
        archive.close()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            if (exchange.requestURI.path != path) {
                exchange.sendResponseHeaders(404, -1)
                return
            }

            val method = exchange.requestMethod
            if (method != "GET" && method != "HEAD") {
                exchange.responseHeaders.set("Allow", "GET, HEAD")
                exchange.sendResponseHeaders(405, -1)
                return
            }

            exchange.responseHeaders.set("Content-Type", "application/zip")
            exchange.responseHeaders.set("Content-Disposition", "attachment; filename=\"aechronis-resource-pack.zip\"")
            exchange.responseHeaders.set("Cache-Control", "public, max-age=31536000, immutable")
            exchange.responseHeaders.set("ETag", "\"${archive.hash}\"")

            if (method == "HEAD") {
                exchange.responseHeaders.set("Content-Length", archive.size.toString())
                exchange.sendResponseHeaders(200, -1)
                return
            }

            exchange.sendResponseHeaders(200, archive.size)
            Files.newInputStream(archive.path).use { input ->
                input.transferTo(exchange.responseBody)
            }
        }
    }

    companion object {
        private val RESOURCE_PACK_ID =
            UUID.nameUUIDFromBytes("aechronis:resource-pack".toByteArray(StandardCharsets.UTF_8))

        fun start(
            directory: Path,
            address: InetSocketAddress,
            publicBaseUri: URI? = null,
        ): ResourcePackServer {
            val normalizedPublicBaseUri = publicBaseUri?.normalizePublicBaseUri()
            val archive = ResourcePackArchive.create(directory)
            var server: HttpServer? = null
            var executor: ExecutorService? = null

            return try {
                server = HttpServer.create(address, 0)
                executor = Executors.newVirtualThreadPerTaskExecutor()
                val result = ResourcePackServer(server, executor, archive, normalizedPublicBaseUri)
                server.executor = executor
                server.createContext("/resource-pack/", result::handle)
                server.start()
                result
            } catch (exception: Exception) {
                server?.stop(0)
                executor?.close()
                archive.close()
                throw exception
            }
        }
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
                                entry.time = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis()
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
