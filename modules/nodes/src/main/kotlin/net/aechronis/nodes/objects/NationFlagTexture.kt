package net.aechronis.nodes.objects

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Semaphore
import javax.imageio.ImageIO

internal typealias FlagTexture = ByteArray

/** Fetches, validates, and caches a flag texture. Call only from an I/O worker. */
internal object NationFlagTexture {
    private const val MAX_DOWNLOAD_BYTES = 512 * 1024
    private const val MAX_SOURCE_DIMENSION = 2048

    // The cloth model is one fixed 2.7:1 shape (see flag-textures/flag_geometry.json) — the
    // widest that fits Minecraft's -16..32 item model bounds from this pole's anchor point; a
    // narrower source image is drawn at its real aspect ratio from the pole-side edge and the
    // rest of the canvas (the far/tip side) is left transparent, so the cloth appears to taper
    // rather than stretching the image to fill the whole model.
    private const val CLOTH_HEIGHT = 20
    private const val CLOTH_WIDTH = 54

    // Flat cap rather than tracking per-flag usage. Each entry is a tiny PNG, so this is cheap to
    // raise if a server ever ends up with more flagged nations/other-flags than this.
    private const val MAX_CACHED = 512
    private val retryDelay = Duration.ofSeconds(30).toNanos()
    private val downloads = Semaphore(4)
    private val cache = LinkedHashMap<UUID, Cached>(MAX_CACHED, .75f, true)

    private data class Cached(val texture: FlagTexture?, val url: String, val expiresAt: Long)

    /** Null means the flag could not be resolved; it's simply left out of the pack. */
    fun resolve(id: UUID, url: String): FlagTexture? {
        cached(id)?.takeIf { it.url == url }?.let { return it.texture }
        try {
            downloads.acquire()
            try {
                cached(id)?.takeIf { it.url == url }?.let { return it.texture }
                val texture = normalize(download(validate(url)))
                remember(id, Cached(texture, url, Long.MAX_VALUE))
                return texture
            } finally {
                downloads.release()
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (exception: Exception) {
            remember(id, Cached(null, url, System.nanoTime() + retryDelay))
            System.err.println("[NationFlag] Could not load flag for $id: ${exception.javaClass.simpleName}: ${exception.message}")
            return null
        }
    }

    fun invalidate(id: UUID) {
        synchronized(this) { cache.remove(id) }
    }

    /** Rejects anything but a public https URL — the URL comes from saved data and can point anywhere, so treat it as untrusted input. */
    private fun validate(url: String): URI {
        val uri = URI(url)
        require(uri.scheme == "https") { "Flag URL must be https" }
        val host = requireNotNull(uri.host) { "Flag URL has no host" }
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty()) { "Flag URL host did not resolve" }
        for (address in addresses) {
            require(!isPrivate(address)) { "Flag URL resolves to a private address" }
        }
        return uri
    }

    private fun isPrivate(address: InetAddress): Boolean {
        val reserved = listOf(address.isLoopbackAddress, address.isSiteLocalAddress, address.isLinkLocalAddress, address.isMulticastAddress, address.isAnyLocalAddress)
        if (reserved.any { it }) return true
        val bytes = address.address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            // 0.0.0.0/8 and carrier-grade NAT (100.64.0.0/10)
            return first == 0 || (first == 100 && second in 64..127)
        }
        // IPv6 unique-local (fc00::/7)
        return (bytes[0].toInt() and 0xfe) == 0xfc
    }

    private fun download(uri: URI): BufferedImage {
        val (contentType, bytes) = fetch(uri)
        require(!contentType.startsWith("image/svg+xml", ignoreCase = true)) { "SVG flags aren't supported, use a PNG" }
        val image = decode(bytes)
        require(image.width in 1..MAX_SOURCE_DIMENSION && image.height in 1..MAX_SOURCE_DIMENSION) { "Flag image dimensions out of range" }
        return image
    }

    private fun fetch(uri: URI): Pair<String, ByteArray> {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        // Java's default "Java/25..." User-Agent gets 403'd by most image CDNs (flagcdn included).
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Aechronis)")
        try {
            require(connection.responseCode == 200) { "Flag download failed (${connection.responseCode})" }
            val contentType = connection.contentType
            require(contentType != null && contentType.startsWith("image/")) { "Flag URL did not return an image" }
            require(connection.contentLengthLong <= MAX_DOWNLOAD_BYTES) { "Flag image is too large" }
            val bytes =
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_DOWNLOAD_BYTES) { "Flag image is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            return contentType to bytes
        } finally {
            connection.disconnect()
        }
    }

    // Reads the declared size from the header first, so a tiny file that claims to be enormous
    // is rejected before ImageIO allocates the full bitmap.
    private fun decode(bytes: ByteArray): BufferedImage {
        val input = requireNotNull(ImageIO.createImageInputStream(ByteArrayInputStream(bytes))) { "Flag URL is not a decodable image" }
        input.use {
            val reader = requireNotNull(ImageIO.getImageReaders(input).asSequence().firstOrNull()) { "Flag URL is not a decodable image" }
            try {
                reader.input = input
                require(reader.getWidth(0) in 1..MAX_SOURCE_DIMENSION && reader.getHeight(0) in 1..MAX_SOURCE_DIMENSION) {
                    "Flag image dimensions out of range"
                }
                return reader.read(0)
            } finally {
                reader.dispose()
            }
        }
    }

    private fun normalize(source: BufferedImage): ByteArray {
        val drawnWidth = (CLOTH_HEIGHT.toLong() * source.width / source.height).toInt().coerceIn(1, CLOTH_WIDTH)
        val canvas = BufferedImage(CLOTH_WIDTH, CLOTH_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = canvas.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            // Drawn from x=0 (the pole side); anything past drawnWidth stays transparent, which is
            // the far/tip side of the cloth mesh, so a narrower flag appears to taper rather than
            // stretching to fill the full 2.7:1 model.
            graphics.drawImage(source, 0, 0, drawnWidth, CLOTH_HEIGHT, null)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(canvas, "png", output)) { "PNG writer is unavailable" }
            output.toByteArray()
        }
    }

    @Synchronized
    private fun cached(id: UUID): Cached? {
        val value = cache[id] ?: return null
        if (value.expiresAt == Long.MAX_VALUE || value.expiresAt - System.nanoTime() > 0) return value
        cache.remove(id)
        return null
    }

    @Synchronized
    private fun remember(
        id: UUID,
        value: Cached,
    ) {
        cache[id] = value
        while (cache.size > MAX_CACHED) cache.remove(cache.keys.first())
    }
}
