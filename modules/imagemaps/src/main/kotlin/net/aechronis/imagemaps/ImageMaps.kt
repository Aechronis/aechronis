package net.aechronis.imagemaps

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.aechronis.server.modules.ModulePermissions
import net.aechronis.utils.JsonRegistryFile
import net.aechronis.vanilla.managers.ItemFrames
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.MapDataPacket
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

class ImageMapException(
    message: String,
) : Exception(message)

/** Player-uploaded images stored as vanilla map tiles, shown through filled maps in item frames. */
object ImageMaps {
    const val USE_PERMISSION = "images.use"
    const val BYPASS_PERMISSION = "images.bypass"

    /** LuckPerms group to image limit, highest rank first; staff with BYPASS_PERMISSION have no limit. */
    val TIERS = listOf("legend" to Int.MAX_VALUE, "vip" to 10, "supporter-plus" to 3, "supporter" to 1)

    // Far above any vanilla map id so uploaded ids never collide with real maps.
    private const val FIRST_MAP_ID = 1_000_000
    private const val TILE_BYTES = ImageQuantizer.TILE * ImageQuantizer.TILE
    private val NAME = Regex("[A-Za-z0-9_-]{1,24}")

    @Serializable
    data class Image(
        val name: String,
        val owner: String,
        val cols: Int,
        val rows: Int,
        val firstMapId: Int,
        val created: Long,
        val ownerName: String = "",
    ) {
        val mapIds: IntRange get() = firstMapId until firstMapId + cols * rows
    }

    /** An upload waiting for staff approval; its tiles live in `pending/<id>.bin` until approved or rejected. */
    @Serializable
    data class Pending(
        val id: String,
        val name: String,
        val owner: String,
        val ownerName: String,
        val cols: Int,
        val rows: Int,
        val created: Long,
    )

    private val images = linkedMapOf<Int, Image>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val storage = JsonRegistryFile("imagemaps")

    // ponytail: tiles stay cached for the process lifetime (16 KB each); add eviction if galleries get large.
    private val tileCache = ConcurrentHashMap<Int, ByteArray>()
    private val sent = ConcurrentHashMap<UUID, MutableSet<Int>>()
    private lateinit var dir: Path

    fun init(dir: Path) {
        this.dir = dir
        Files.createDirectories(dir.resolve("maps"))
        Files.createDirectories(dir.resolve("pending"))
        ModulePermissions.register(USE_PERMISSION, BYPASS_PERMISSION)
        storage.load(dir.resolve("index.json"), images) { contents ->
            Json.decodeFromString<List<Image>>(contents).filter(::valid).associateBy(Image::firstMapId)
        }
        pending.clear()
        Files.list(dir.resolve("pending")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.forEach { path ->
                runCatching { Json.decodeFromString<Pending>(Files.readString(path)) }
                    .getOrNull()
                    ?.takeIf(::valid)
                    ?.let { pending[it.id] = it }
            }
        }
        ItemFrames.viewerHook = { player, item -> item.get(DataComponents.MAP_ID)?.let { send(player, it) } }
    }

    fun shutdown() {
        ItemFrames.viewerHook = null
        tileCache.clear()
        sent.clear()
        pending.clear()
        synchronized(images) { images.clear() }
    }

    fun validName(name: String) = NAME.matches(name)

    /** Every stored image, for the public `/images` browser. */
    fun all(): List<Image> = synchronized(images) { images.values.sortedBy { it.name.lowercase() } }

    fun list(owner: UUID): List<Image> =
        synchronized(images) { images.values.filter { it.owner == owner.toString() }.sortedBy(Image::name) }

    fun find(
        owner: UUID,
        name: String,
    ): Image? = list(owner).firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * Parks [tiles] for staff review; throws [ImageMapException] with a player-facing reason on rejection.
     * Pending uploads count toward the quota so one player can't flood the review channel.
     */
    fun submit(
        owner: UUID,
        ownerName: String,
        name: String,
        tiles: Tiles,
        limit: Int,
    ): Pending =
        synchronized(images) {
            if (!validName(name)) throw ImageMapException("Names are 1-24 letters, digits, '_' or '-'.")
            if (!storage.canSave) throw ImageMapException("Image storage is unavailable.")
            val waiting = pending.values.filter { it.owner == owner.toString() }
            if (find(owner, name) != null || waiting.any { it.name.equals(name, ignoreCase = true) }) {
                throw ImageMapException("You already have an image named $name.")
            }
            if (list(owner).size + waiting.size >= limit) {
                throw ImageMapException(
                    "Your rank allows $limit image${if (limit == 1) "" else "s"}, counting ones awaiting review. Delete one first.",
                )
            }
            val id = UUID.randomUUID().toString().take(8)
            val entry = Pending(id, name, owner.toString(), ownerName, tiles.cols, tiles.rows, System.currentTimeMillis())
            Files.write(pendingPath(id, "bin"), tiles.tiles.fold(ByteArray(0)) { all, tile -> all + tile })
            Files.writeString(pendingPath(id, "json"), Json.encodeToString(entry))
            pending[id] = entry
            entry
        }

    /** What staff review: the quantized tiles stitched back into one PNG, exactly as they will look in frames. */
    fun preview(id: String): ByteArray? {
        val entry = pending[id] ?: return null
        val out = ByteArrayOutputStream()
        ImageIO.write(ImageQuantizer.render(readPending(entry)), "png", out)
        return out.toByteArray()
    }

    /** Publishes a pending upload to the gallery; throws [ImageMapException] if it was already handled. */
    fun approve(id: String): Image =
        synchronized(images) {
            val entry = pending[id] ?: throw ImageMapException("That upload was already handled.")
            val owner = UUID.fromString(entry.owner)
            if (find(owner, entry.name) != null) throw ImageMapException("${entry.ownerName} already has an image named ${entry.name}.")
            val tiles = readPending(entry)
            val first = images.values.maxOfOrNull { it.mapIds.last + 1 } ?: FIRST_MAP_ID
            val image = Image(entry.name, entry.owner, entry.cols, entry.rows, first, System.currentTimeMillis(), entry.ownerName)
            tiles.tiles.forEachIndexed { index, data -> Files.write(tilePath(first + index), data) }
            images[first] = image
            save()
            discard(id)
            image
        }

    /** Drops a pending upload; null if it was already handled. */
    fun reject(id: String): Pending? = synchronized(images) { pending[id]?.also { discard(id) } }

    /** Tells the uploader the outcome if they are online; offline players just find the image in /images. */
    fun notifyOwner(
        owner: String,
        message: String,
    ) {
        MinecraftServer
            .getConnectionManager()
            .getOnlinePlayerByUuid(UUID.fromString(owner))
            ?.sendMessage(Component.text(message, NamedTextColor.LIGHT_PURPLE))
    }

    private fun discard(id: String) {
        pending.remove(id)
        Files.deleteIfExists(pendingPath(id, "bin"))
        Files.deleteIfExists(pendingPath(id, "json"))
    }

    private fun readPending(entry: Pending): Tiles {
        val bytes = Files.readAllBytes(pendingPath(entry.id, "bin"))
        return Tiles(entry.cols, entry.rows, List(entry.cols * entry.rows) { bytes.copyOfRange(it * TILE_BYTES, (it + 1) * TILE_BYTES) })
    }

    private fun pendingPath(
        id: String,
        extension: String,
    ) = dir.resolve("pending").resolve("$id.$extension")

    fun delete(
        owner: UUID,
        name: String,
    ): Boolean =
        synchronized(images) {
            val image = find(owner, name) ?: return false
            images.remove(image.firstMapId)
            save()
            image.mapIds.forEach { id ->
                tileCache.remove(id)
                Files.deleteIfExists(tilePath(id))
                // A later upload may reuse these ids; make sure viewers get the new pixels.
                sent.values.forEach { it.remove(id) }
            }
            true
        }

    /** One filled map per tile, row-major; the lore says where each tile goes on the wall. */
    fun items(image: Image): List<ItemStack> =
        image.mapIds.mapIndexed { index, id ->
            ItemStack
                .of(Material.FILLED_MAP)
                .with(DataComponents.MAP_ID, id)
                .with(
                    DataComponents.CUSTOM_NAME,
                    Component.text(image.name, NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false),
                ).with(
                    DataComponents.LORE,
                    listOf(
                        Component
                            .text("Row ${index / image.cols + 1}, column ${index % image.cols + 1}", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    ),
                )
        }

    /** Sends the tile's pixels once per session; the client keeps map data by id after that. */
    fun send(
        player: Player,
        mapId: Int,
    ) {
        val data = tile(mapId) ?: return
        if (!sent.computeIfAbsent(player.uuid) { ConcurrentHashMap.newKeySet() }.add(mapId)) return
        val size = ImageQuantizer.TILE.toByte()
        player.sendPacket(MapDataPacket(mapId, 0, true, false, emptyList(), MapDataPacket.ColorContent(size, size, 0, 0, data)))
    }

    fun sendInventory(player: Player) {
        player.inventory.itemStacks.forEach { stack -> stack.get(DataComponents.MAP_ID)?.let { send(player, it) } }
    }

    fun forget(player: Player) {
        sent.remove(player.uuid)
    }

    private fun tile(mapId: Int): ByteArray? {
        tileCache[mapId]?.let { return it }
        if (synchronized(images) { images.values.none { mapId in it.mapIds } }) return null
        val data = runCatching { Files.readAllBytes(tilePath(mapId)) }.getOrNull()?.takeIf { it.size == TILE_BYTES } ?: return null
        return tileCache.putIfAbsent(mapId, data) ?: data
    }

    private fun tilePath(mapId: Int) = dir.resolve("maps").resolve("$mapId.bin")

    private fun save() = storage.save { Json.encodeToString(synchronized(images) { images.values.toList() }) }

    private fun valid(image: Image) =
        validName(image.name) &&
            runCatching { UUID.fromString(image.owner) }.isSuccess &&
            image.cols in 1..ImageQuantizer.MAX_COLS &&
            image.rows in 1..ImageQuantizer.MAX_ROWS &&
            image.firstMapId >= FIRST_MAP_ID

    private fun valid(entry: Pending) =
        entry.id.matches(Regex("[0-9a-f]{8}")) &&
            validName(entry.name) &&
            runCatching { UUID.fromString(entry.owner) }.isSuccess &&
            entry.cols in 1..ImageQuantizer.MAX_COLS &&
            entry.rows in 1..ImageQuantizer.MAX_ROWS
}
