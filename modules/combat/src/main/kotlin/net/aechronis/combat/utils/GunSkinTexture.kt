package net.aechronis.combat.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minestom.server.entity.PlayerSkin
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** Resolves the owner's skin for a texture-only gun pack. Call only from an I/O worker. */
internal object GunSkinTexture {
    /** Shared cached image; callers must treat its pixels as read-only. */
    data class Resolved(
        val image: BufferedImage,
        val slim: Boolean,
    )

    private data class Cached(
        val skin: Resolved?,
        val expiresAt: Long,
    )

    private data class Remote(
        val uri: URI,
        val slim: Boolean,
    )

    private const val MAX_DOWNLOAD_BYTES = 512 * 1024
    private val retryDelay = Duration.ofSeconds(30).toNanos()
    private val downloads = Semaphore(4)
    private val cache = LinkedHashMap<String, Cached>(128, .75f, true)
    private val defaultNames = listOf("alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri")

    /** Null means the custom skin could not be resolved; keep the skin-ready atlas flag clear. */
    fun resolve(
        uuid: UUID,
        skin: PlayerSkin?,
    ): Resolved? {
        if (skin == null) return defaultSkin(uuid)
        val remote =
            try {
                parse(skin) ?: return defaultSkin(uuid)
            } catch (exception: Exception) {
                System.err.println("[GunSkin] Invalid skin profile for $uuid: ${exception.javaClass.simpleName}")
                return null
            }
        val key = "${remote.uri.path}:${remote.slim}"
        cached(key)?.let { return it.skin }
        try {
            if (!downloads.tryAcquire(5, TimeUnit.SECONDS)) return null
            try {
                cached(key)?.let { return it.skin }
                val resolved = Resolved(normalize(download(remote.uri)), remote.slim)
                remember(key, Cached(resolved, Long.MAX_VALUE))
                return resolved
            } finally {
                downloads.release()
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (exception: Exception) {
            remember(key, Cached(null, System.nanoTime() + retryDelay))
            System.err.println("[GunSkin] Could not load skin for $uuid: ${exception.javaClass.simpleName}")
            return null
        }
    }

    private fun parse(skin: PlayerSkin): Remote? {
        require(skin.textures().length <= 32 * 1024) { "Skin profile is too large" }
        val payload = String(Base64.getDecoder().decode(skin.textures()), StandardCharsets.UTF_8)
        val textures = Json.parseToJsonElement(payload).jsonObject["textures"] ?: return null
        if (textures == JsonNull) return null
        val texture = textures.jsonObject["SKIN"]?.jsonObject ?: return null
        val uri = URI(texture["url"]!!.jsonPrimitive.content)
        require(uri.scheme == "https" || uri.scheme == "http") { "Unsupported skin URL scheme" }
        require(uri.host == "textures.minecraft.net" && uri.userInfo == null) { "Unsupported skin URL host" }
        require(uri.port == -1 && uri.query == null && uri.fragment == null) { "Unsupported skin URL components" }
        require(uri.path.matches(Regex("/texture/[0-9a-fA-F]{32,64}"))) { "Invalid skin texture path" }
        val slim =
            texture["metadata"]
                ?.jsonObject
                ?.get("model")
                ?.jsonPrimitive
                ?.contentOrNull == "slim"
        val secureUri = if (uri.scheme == "http") URI("https", uri.authority, uri.path, null, null) else uri
        return Remote(secureUri, slim)
    }

    private fun download(uri: URI): BufferedImage {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == 200) { "Skin download failed" }
            require(connection.contentLengthLong <= MAX_DOWNLOAD_BYTES) { "Skin download is too large" }
            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            val bytes =
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                        require(remaining > 0) { "Skin download timed out" }
                        connection.readTimeout = remaining.coerceAtMost(5_000).toInt().coerceAtLeast(1)
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_DOWNLOAD_BYTES) { "Skin download is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            return readPng(bytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun readPng(bytes: ByteArray): BufferedImage =
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "Skin is not an image" }
            val reader = readers.next()
            try {
                require(reader.formatName.equals("png", ignoreCase = true)) { "Skin is not a PNG" }
                reader.input = input
                require(reader.getWidth(0) == 64 && reader.getHeight(0) in listOf(32, 64)) { "Invalid skin dimensions" }
                reader.read(0)
            } finally {
                reader.dispose()
            }
        }

    private fun defaultSkin(uuid: UUID): Resolved {
        // Minecraft 26.2 DefaultPlayerSkin.get(UUID): nine slim entries, then nine wide entries.
        val index = Math.floorMod(uuid.hashCode(), 18)
        val slim = index < 9
        val name = "${if (slim) "slim" else "wide"}/${defaultNames[index % 9]}"
        val key = "minecraft-default:$name"
        cached(key)?.skin?.let { return it }
        val bytes = checkNotNull(javaClass.getResourceAsStream("/gun-skins/default/$name.png")).use { it.readBytes() }
        // Built-in defaults are resource textures; vanilla does not run downloaded-skin alpha fixes on them.
        val resolved = Resolved(readPng(bytes), slim)
        remember(key, Cached(resolved, Long.MAX_VALUE))
        return resolved
    }

    /** Mirrors the 26.2 SkinTextureDownloader legacy expansion and alpha rules. */
    fun normalize(source: BufferedImage): BufferedImage {
        require(source.width == 64 && source.height in listOf(32, 64)) { "Invalid skin dimensions" }
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 64, source.height, source.getRGB(0, 0, 64, source.height, null, 0, 64), 0, 64)
        val legacy = source.height == 32
        if (legacy) {
            // Source rectangle, destination offset, width, height; all are mirrored horizontally.
            val rectangles =
                arrayOf(
                    intArrayOf(4, 16, 16, 32, 4, 4),
                    intArrayOf(8, 16, 16, 32, 4, 4),
                    intArrayOf(0, 20, 24, 32, 4, 12),
                    intArrayOf(4, 20, 16, 32, 4, 12),
                    intArrayOf(8, 20, 8, 32, 4, 12),
                    intArrayOf(12, 20, 16, 32, 4, 12),
                    intArrayOf(44, 16, -8, 32, 4, 4),
                    intArrayOf(48, 16, -8, 32, 4, 4),
                    intArrayOf(40, 20, 0, 32, 4, 12),
                    intArrayOf(44, 20, -8, 32, 4, 12),
                    intArrayOf(48, 20, -16, 32, 4, 12),
                    intArrayOf(52, 20, -8, 32, 4, 12),
                )
            for (rectangle in rectangles) {
                val (x, y, dx, dy, width) = rectangle
                val height = rectangle[5]
                for (yy in 0 until height) {
                    for (xx in 0 until width) image.setRGB(x + dx + width - xx - 1, y + dy + yy, image.getRGB(x + xx, y + yy))
                }
            }
        }
        opaque(image, 0, 0, 32, 16)
        if (legacy && (0 until 32).all { y -> (32 until 64).all { x -> image.getRGB(x, y) ushr 24 >= 128 } }) {
            for (y in 0 until 32) for (x in 32 until 64) image.setRGB(x, y, image.getRGB(x, y) and 0x00ffffff)
        }
        opaque(image, 0, 16, 64, 32)
        opaque(image, 16, 48, 48, 64)
        return image
    }

    private fun opaque(
        image: BufferedImage,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        for (y in y0 until y1) for (x in x0 until x1) image.setRGB(x, y, image.getRGB(x, y) or -0x1000000)
    }

    @Synchronized
    private fun cached(key: String): Cached? {
        val value = cache[key] ?: return null
        if (value.expiresAt > System.nanoTime()) return value
        cache.remove(key)
        return null
    }

    @Synchronized
    private fun remember(
        key: String,
        value: Cached,
    ) {
        cache[key] = value
        while (cache.size > 128) cache.remove(cache.keys.first())
    }
}
