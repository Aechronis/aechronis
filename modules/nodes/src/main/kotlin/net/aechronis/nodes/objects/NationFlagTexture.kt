package net.aechronis.nodes.objects

import com.kitfox.svg.SVGUniverse
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
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO

/** The flag cloth model comes in two aspect ratios; each nation's texture picks the closer fit. */
internal enum class FlagModelVariant(val resourceName: String, val aspectRatio: Double) {
    TWO_TO_ONE("2_1", 2.0),
    THREE_TO_TWO("3_2", 1.5),
    ;

    companion object {
        fun closestTo(width: Int, height: Int): FlagModelVariant {
            val aspect = width.toDouble() / height.toDouble()
            return entries.minBy { kotlin.math.abs(it.aspectRatio - aspect) }
        }
    }
}

internal data class FlagTexture(val png: ByteArray, val variant: FlagModelVariant)

/** Fetches, validates, and caches a nation's flag texture. Call only from an I/O worker. */
internal object NationFlagTexture {
    private const val MAX_DOWNLOAD_BYTES = 512 * 1024
    private const val MAX_SOURCE_DIMENSION = 2048

    // Matches the flag-cloth model's own authored texture resolution (see flag-textures/*.json),
    // so no texture_size override is needed in the generated item model.
    private const val CLOTH_SIZE = 16

    // Flag-icon sites (e.g. flagcdn.com, the source of most seeded nation flags) commonly serve
    // SVG. ImageIO can't decode that, so SVGs are rasterized to this square before normalize().
    private const val SVG_RASTER_SIZE = 256

    // Flat cap rather than tracking per-nation usage. Each entry is a tiny 16x16 PNG, so this is
    // cheap to raise if a server ever ends up with more flagged nations than this.
    private const val MAX_CACHED_NATIONS = 512
    private val retryDelay = Duration.ofSeconds(30).toNanos()
    private val downloads = Semaphore(4)
    private val cache = LinkedHashMap<UUID, Cached>(MAX_CACHED_NATIONS, .75f, true)

    init {
        // Unresolved xlink:href gradient stops (common on real flag SVGs) are caught and logged by
        // the library itself with a full stack trace per occurrence; that's not our failure to report.
        Logger.getLogger("com.kitfox.svg").level = Level.SEVERE
    }

    private data class Cached(val texture: FlagTexture?, val url: String, val expiresAt: Long)

    /** Null means the flag could not be resolved; the nation is simply left out of the pack. */
    fun resolve(nationUuid: UUID, url: String): FlagTexture? {
        cached(nationUuid)?.takeIf { it.url == url }?.let { return it.texture }
        try {
            if (!downloads.tryAcquire(5, TimeUnit.SECONDS)) return null
            try {
                cached(nationUuid)?.takeIf { it.url == url }?.let { return it.texture }
                val source = download(validate(url))
                val texture = FlagTexture(normalize(source), FlagModelVariant.closestTo(source.width, source.height))
                remember(nationUuid, Cached(texture, url, Long.MAX_VALUE))
                return texture
            } finally {
                downloads.release()
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (exception: Exception) {
            remember(nationUuid, Cached(null, url, System.nanoTime() + retryDelay))
            System.err.println("[NationFlag] Could not load flag for $nationUuid: ${exception.javaClass.simpleName}: ${exception.message}")
            return null
        }
    }

    fun invalidate(nationUuid: UUID) {
        synchronized(this) { cache.remove(nationUuid) }
    }

    /** Rejects anything but a public https URL — leaders can set this to whatever they want, so treat it as untrusted input. */
    private fun validate(url: String): URI {
        val uri = URI(url)
        require(uri.scheme == "https") { "Flag URL must be https" }
        val host = requireNotNull(uri.host) { "Flag URL has no host" }
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty()) { "Flag URL host did not resolve" }
        for (address in addresses) {
            require(
                !address.isLoopbackAddress &&
                    !address.isSiteLocalAddress &&
                    !address.isLinkLocalAddress &&
                    !address.isMulticastAddress &&
                    !address.isAnyLocalAddress,
            ) { "Flag URL resolves to a private address" }
        }
        return uri
    }

    private fun download(uri: URI): BufferedImage {
        val (contentType, bytes) = fetch(uri)
        val image =
            if (contentType.startsWith("image/svg+xml", ignoreCase = true)) {
                try {
                    rasterizeSvg(bytes)
                } catch (svgError: Exception) {
                    flagcdnPngFallback(uri) ?: throw svgError
                }
            } else {
                requireNotNull(ImageIO.read(ByteArrayInputStream(bytes))) { "Flag URL is not a decodable image" }
            }
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

    // flagcdn.com's SVGs chain gradient stops via xlink:href, which svgSalamander can't resolve
    // (throws "User must specify at least 2 colors" on flags with a detailed coat of arms, e.g.
    // Guatemala). Its own PNG endpoint renders the same flag without that gradient limitation.
    private fun flagcdnPngFallback(svgUri: URI): BufferedImage? {
        if (svgUri.host != "flagcdn.com" || !svgUri.path.endsWith(".svg")) return null
        val code = svgUri.path.removePrefix("/").removeSuffix(".svg")
        if (code.isEmpty() || !code.all { it.isLetterOrDigit() }) return null
        val (contentType, bytes) = fetch(validate("https://flagcdn.com/w320/$code.png"))
        if (!contentType.startsWith("image/")) return null
        return ImageIO.read(ByteArrayInputStream(bytes))
    }

    private fun rasterizeSvg(bytes: ByteArray): BufferedImage {
        val universe = SVGUniverse()
        val diagram =
            requireNotNull(universe.getDiagram(universe.loadSVG(ByteArrayInputStream(bytes), "flag-${System.nanoTime()}"))) {
                "Flag URL is not a decodable SVG"
            }
        val image = BufferedImage(SVG_RASTER_SIZE, SVG_RASTER_SIZE, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val scaleX = if (diagram.width > 0) SVG_RASTER_SIZE / diagram.width else 1f
            val scaleY = if (diagram.height > 0) SVG_RASTER_SIZE / diagram.height else 1f
            graphics.scale(scaleX.toDouble(), scaleY.toDouble())
            diagram.render(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun normalize(source: BufferedImage): ByteArray {
        val icon = BufferedImage(CLOTH_SIZE, CLOTH_SIZE, BufferedImage.TYPE_INT_ARGB)
        val graphics = icon.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            // The chosen model variant already matches the source's aspect ratio, so this only
            // absorbs the remainder (e.g. a flag that's neither exactly 2:1 nor 3:2).
            graphics.drawImage(source, 0, 0, CLOTH_SIZE, CLOTH_SIZE, null)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(icon, "png", output)) { "PNG writer is unavailable" }
            output.toByteArray()
        }
    }

    @Synchronized
    private fun cached(nationUuid: UUID): Cached? {
        val value = cache[nationUuid] ?: return null
        if (value.expiresAt > System.nanoTime()) return value
        cache.remove(nationUuid)
        return null
    }

    @Synchronized
    private fun remember(
        nationUuid: UUID,
        value: Cached,
    ) {
        cache[nationUuid] = value
        while (cache.size > MAX_CACHED_NATIONS) cache.remove(cache.keys.first())
    }
}
