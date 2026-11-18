package net.aechronis.discord

import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import dev.kord.common.entity.Snowflake
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

/** Anyone who can post in one staff-only channel drops .schem files into WorldEdit's folder, then //schem load and paste in-game. */
internal class SchemUpload(
    val channel: Snowflake,
    private val dir: Path = Path.of("worldedit", "schematics"),
) {
    suspend fun upload(
        http: HttpClient,
        uploader: String,
        filename: String,
        size: Int,
        url: String,
        requested: String?,
    ): String {
        if (!filename.endsWith(".schem", ignoreCase = true)) return "Only .schem (Sponge) schematics are supported."
        if (size !in 1..MAX_BYTES) return "Schematic must be under ${MAX_BYTES / 1_000_000} MB."
        val name = clean(requested?.takeIf { it.isNotBlank() } ?: filename.dropLast(".schem".length))
        if (name.isEmpty()) return "Pick a name using letters, numbers, - or _."
        val target = dir.resolve("$name.schem")
        if (Files.exists(target)) return "A schematic named `$name` already exists; pick another name."
        return withContext(Dispatchers.IO) {
            Files.createDirectories(dir)
            val temp = Files.createTempFile(dir, "upload-", ".schem")
            try {
                val bytes = http.get(url).body<ByteArray>()
                if (bytes.size > MAX_BYTES) return@withContext "Schematic must be under ${MAX_BYTES / 1_000_000} MB."
                Files.write(temp, bytes)
                if (ClipboardFormats.findByFile(temp.toFile()) == null) {
                    return@withContext "Could not read that file as a .schem schematic."
                }
                // No REPLACE_EXISTING: two uploads racing for one name can't overwrite each other.
                Files.move(temp, target)
                println("[Discord] $uploader uploaded schematic $name")
                "Uploaded as `$name`. In-game: `//schem load $name.schem`, then `//paste`."
            } catch (_: FileAlreadyExistsException) {
                "A schematic named `$name` already exists; pick another name."
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    companion object {
        const val MAX_BYTES = 5_000_000

        fun clean(name: String): String =
            name
                .lowercase()
                .replace(' ', '_')
                .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
                .take(48)
    }
}
