package net.aechronis.discord

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.aechronis.imagemaps.ImageMapException
import net.aechronis.imagemaps.ImageMaps
import net.aechronis.imagemaps.ImageQuantizer
import net.aechronis.nodes.objects.Resident
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import javax.imageio.ImageIO

/** /imageupload: a ranked, linked player turns an attached PNG or JPEG into map tiles for item frames. */
internal object ImageUpload {
    private const val MAX_BYTES = 5_000_000
    private const val MAX_DIMENSION = 4096
    private val EXTENSIONS = setOf("png", "jpg", "jpeg")

    // Attachments only ever come from Discord's CDN; refusing other hosts keeps this from fetching internal URLs.
    private val HOSTS = setOf("cdn.discordapp.com", "media.discordapp.net")

    suspend fun upload(
        links: AccountLinks,
        nodes: NodesCommands,
        http: HttpClient,
        discordId: String,
        filename: String?,
        size: Int,
        url: String?,
        name: String,
        cols: Int,
        rows: Int?,
    ): String {
        val uuid = links.player(discordId) ?: return "Not linked. Run /discord link in Minecraft, then /link code:<code> here."
        if (!nodes.allowed(uuid, ImageMaps.UPLOAD_PERMISSION)) return "Your rank can't upload images."
        if (filename == null || url == null || filename.substringAfterLast('.', "").lowercase() !in EXTENSIONS) {
            return "Attach a PNG or JPEG image (animated GIFs aren't supported yet)."
        }
        if (size !in 1..MAX_BYTES) return "Image must be under ${MAX_BYTES / 1_000_000} MB."
        if (!ImageMaps.validName(name)) return "Names are 1-24 letters, digits, '_' or '-'."
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host?.lowercase() !in HOSTS) return "That attachment link isn't from Discord."
        val bypass = nodes.allowed(uuid, ImageMaps.BYPASS_PERMISSION)
        val ownerName = onGameThread { Resident.fromUuid(uuid)?.name } ?: "unknown"
        return try {
            val bytes = http.get(url).body<ByteArray>()
            if (bytes.size > MAX_BYTES) return "Image must be under ${MAX_BYTES / 1_000_000} MB."
            val image =
                withContext(Dispatchers.Default) {
                    val tiles = ImageQuantizer.quantize(decode(bytes), cols, rows)
                    ImageMaps.add(uuid, ownerName, name, tiles, bypass)
                }
            println("[Discord] $uuid uploaded image $name (${image.cols}x${image.rows})")
            "Uploaded `${image.name}` as ${image.cols}x${image.rows} tiles. Anyone can take it with `/images` in game; put the tiles in item frames row by row, left to right."
        } catch (error: ImageMapException) {
            error.message ?: "Could not store that image."
        } catch (_: IOException) {
            "Could not read that image."
        }
    }

    // Reads the header first so a tiny file can't declare a huge canvas and exhaust memory on decode.
    private fun decode(bytes: ByteArray): BufferedImage {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: throw ImageMapException("Could not read that image.")
            try {
                reader.input = input
                if (reader.getWidth(0) > MAX_DIMENSION || reader.getHeight(0) > MAX_DIMENSION) {
                    throw ImageMapException("Image is larger than ${MAX_DIMENSION}x$MAX_DIMENSION pixels.")
                }
                return reader.read(0)
            } finally {
                reader.dispose()
            }
        }
    }
}
