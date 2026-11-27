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
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.MapDataPacket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ImageMapException(
    message: String,
) : Exception(message)

/** Player-uploaded images stored as vanilla map tiles, shown through filled maps in item frames. */
object ImageMaps {
    const val UPLOAD_PERMISSION = "images.upload"
    const val USE_PERMISSION = "images.use"
    const val BYPASS_PERMISSION = "images.bypass"

    // Rank tiers are undecided; one flat quota for now (staff skip it with BYPASS_PERMISSION).
    const val MAX_IMAGES_PER_PLAYER = 5

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

    private val images = linkedMapOf<Int, Image>()
    private val storage = JsonRegistryFile("imagemaps")

    // ponytail: tiles stay cached for the process lifetime (16 KB each); add eviction if galleries get large.
    private val tileCache = ConcurrentHashMap<Int, ByteArray>()
    private val sent = ConcurrentHashMap<UUID, MutableSet<Int>>()
    private lateinit var dir: Path

    fun init(dir: Path) {
        this.dir = dir
        Files.createDirectories(dir.resolve("maps"))
        ModulePermissions.register(UPLOAD_PERMISSION, USE_PERMISSION, BYPASS_PERMISSION)
        storage.load(dir.resolve("index.json"), images) { contents ->
            Json.decodeFromString<List<Image>>(contents).filter(::valid).associateBy(Image::firstMapId)
        }
        ItemFrames.viewerHook = { player, item -> item.get(DataComponents.MAP_ID)?.let { send(player, it) } }
    }

    fun shutdown() {
        ItemFrames.viewerHook = null
        tileCache.clear()
        sent.clear()
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

    /** Stores [tiles] for [owner]; throws [ImageMapException] with a player-facing reason on rejection. */
    fun add(
        owner: UUID,
        ownerName: String,
        name: String,
        tiles: Tiles,
        bypassQuota: Boolean,
    ): Image =
        synchronized(images) {
            if (!validName(name)) throw ImageMapException("Names are 1-24 letters, digits, '_' or '-'.")
            if (!storage.canSave) throw ImageMapException("Image storage is unavailable.")
            if (find(owner, name) != null) throw ImageMapException("You already have an image named $name.")
            if (list(owner).size >= MAX_IMAGES_PER_PLAYER && !bypassQuota) {
                throw ImageMapException("You can store up to $MAX_IMAGES_PER_PLAYER images. Delete one first.")
            }
            val first = images.values.maxOfOrNull { it.mapIds.last + 1 } ?: FIRST_MAP_ID
            val image = Image(name, owner.toString(), tiles.cols, tiles.rows, first, System.currentTimeMillis(), ownerName)
            tiles.tiles.forEachIndexed { index, data -> Files.write(tilePath(first + index), data) }
            images[first] = image
            save()
            image
        }

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
}
