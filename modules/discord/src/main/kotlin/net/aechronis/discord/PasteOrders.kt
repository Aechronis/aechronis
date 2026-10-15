package net.aechronis.discord

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.function.mask.AbstractMask
import com.sk89q.worldedit.function.mask.Mask
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.session.ClipboardHolder
import com.sk89q.worldedit.world.block.BlockTypes
import dev.kord.common.entity.Snowflake
import io.github.openminigameserver.worldedit.platform.actors.MinestomConsole
import io.github.openminigameserver.worldedit.platform.adapters.MinestomAdapter
import io.github.openminigameserver.worldedit.platform.misc.WorldEditExecutor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.aechronis.gems.Gems
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.minestom.server.instance.InstanceContainer
import java.nio.file.Files
import java.io.IOException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** Gem-priced schematic pastes bought through a Discord ticket. Orders live in memory; a restart just means re-running /paste. */
internal class PasteOrders(
    private val links: AccountLinks,
    private val world: InstanceContainer,
    val ticketCategory: Snowflake,
    /** Optional extra gate; without it anyone who can see the staff channel can decide. */
    val staffRole: Snowflake?,
    /** Where staff review and approve; null keeps the buttons in the ticket channel. */
    val staffChannel: Snowflake?,
    private val dir: Path = Path.of("discord", "pastes"),
    private val history: PasteHistory = PasteHistory(dir.resolve("history.json")),
) {
    class Order(
        val id: String,
        val discordId: String,
        val player: UUID,
        val x: Int,
        val y: Int,
        val z: Int,
        val chunks: Int,
        val cost: Long,
        val town: String,
        val file: Path,
        /** Paid-for footprint; a free repaste may only touch chunks inside it. */
        val allowance: Set<Long>,
        val replaces: PasteHistory.Entry? = null,
    )

    /** Autocomplete choices (label to paste id) for /repaste. */
    fun suggest(discordId: String): List<Pair<String, String>> {
        val player = links.player(discordId) ?: return emptyList()
        return history.forPlayer(player).take(25).mapIndexed { index, e ->
            "#${index + 1} · ${e.x} ${e.y} ${e.z} · ${e.chunks.size} chunk(s) · ${e.town}".take(100) to e.id
        }
    }

    private val pending = ConcurrentHashMap<String, Order>()

    /** Ticket channel of each open order, so the outcome can be reported back there. */
    val ticketOf = ConcurrentHashMap<String, Snowflake>()

    /** Shulkr viewer link of each open order, kept on the staff post after a decision. */
    val viewerOf = ConcurrentHashMap<String, String>()

    /** Optional audit trail, kept in a different Discord server through a webhook. */
    var log: PasteLog? = null

    /** Validates and prices a request. Returns the order or a user-facing rejection. */
    suspend fun prepare(
        http: HttpClient,
        discordId: String,
        filename: String,
        size: Int,
        url: String,
        x: Int,
        y: Int,
        z: Int,
        replacing: String? = null,
    ): Result<Order> {
        val player = links.player(discordId) ?: return fail("Link your account first: /discord link in Minecraft, then /link code:<code> here.")
        val old = replacing?.let { wanted -> history.get(wanted)?.takeIf { it.player == player } ?: return fail("Pick one of your own pastes from the list.") }
        if (old != null && !Files.exists(saved(old.id))) return fail("The original schematic for that paste is missing; ask staff.")
        // A repaste always lands on the original origin.
        @Suppress("NAME_SHADOWING") val x = old?.x ?: x
        @Suppress("NAME_SHADOWING") val y = old?.y ?: y
        @Suppress("NAME_SHADOWING") val z = old?.z ?: z
        if (!filename.endsWith(".schem", ignoreCase = true)) return fail("Only .schem (Sponge) schematics are supported.")
        if (size !in 1..MAX_BYTES) return fail("Schematic must be under ${MAX_BYTES / 1_000_000} MB.")
        val id = UUID.randomUUID().toString()
        val file = dir.resolve("$id.schem")
        try {
            val bytes = withContext(Dispatchers.IO) { http.get(url).body<ByteArray>() }
            if (bytes.size > MAX_BYTES) return fail("Schematic must be under ${MAX_BYTES / 1_000_000} MB.")
            withContext(Dispatchers.IO) {
                Files.createDirectories(dir)
                Files.write(file, bytes)
            }
            if (!withContext(Dispatchers.IO) { inflatedWithinLimit(file) }) {
                discard(file)
                return fail("That schematic is too large once unpacked.")
            }
            val clipboard = load(file) ?: return fail("Could not read that file as a .schem schematic.").also { discard(file) }
            val box = box(clipboard, x, y, z)
            if (box.width > MAX_SIDE || box.length > MAX_SIDE || box.height > MAX_HEIGHT) {
                discard(file)
                return fail("Too large: at most $MAX_SIDE x $MAX_HEIGHT x $MAX_SIDE blocks.")
            }
            val scan = withContext(Dispatchers.Default) { scan(clipboard, x, z) }
            if (scan.isFailure) {
                discard(file)
                return Result.failure(scan.exceptionOrNull()!!)
            }
            val touched = scan.getOrThrow()
            if (old != null && !old.chunks.containsAll(touched)) {
                discard(file)
                return fail("A free repaste may only use the chunks of the original paste. This one reaches outside them; buy the extra chunks with /paste.")
            }
            val chunks = touched.size
            val cost = if (old != null) 0L else chunks * COST_PER_CHUNK
            val allowance = old?.chunks ?: touched
            val order = Order(id, discordId, player, x, y, z, chunks, cost, "", file, allowance, old)
            val town =
                try {
                    onGameThread { check(order, box) }
                } catch (rejection: IllegalStateException) {
                    discard(file)
                    return Result.failure(rejection)
                }
            val ready = Order(id, discordId, player, x, y, z, chunks, cost, town, file, allowance, old)
            // One open order per player; a new request replaces the old one.
            pending.values.filter { it.player == player }.forEach { pending.remove(it.id)?.let { old -> discard(old.file) } }
            pending[id] = ready
            journal(if (old != null) "repaste requested" else "requested", ready)
            return Result.success(ready)
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            discard(file)
            throw error
        } catch (error: Exception) {
            discard(file)
            System.err.println("[Discord] Paste prepare failed (${error.javaClass.simpleName})")
            return fail("Could not process that schematic.")
        }
    }

    fun reject(
        id: String,
        by: String,
    ): Boolean = pending.remove(id)?.also { discard(it.file); journal("rejected", it, by) } != null

    /** Charges and pastes. Returns a message for the ticket. Single use: the order is claimed atomically. */
    suspend fun approve(
        id: String,
        by: String,
    ): String {
        val order = pending.remove(id) ?: return "That order was already handled or expired. Ask the player to run /paste again."
        try {
            if (links.player(order.discordId) != order.player) return "The player's account was unlinked. Order cancelled.".also { discard(order.file) }
            val clipboard = withContext(Dispatchers.IO) { load(order.file) } ?: return "Schematic file is unreadable. Order cancelled.".also { discard(order.file) }
            val box = box(clipboard, order.x, order.y, order.z)
            try {
                onGameThread { check(order, box) }
            } catch (rejection: IllegalStateException) {
                discard(order.file)
                return "Cancelled: ${rejection.message}"
            }
            val old = order.replaces
            var previous: Clipboard? = null
            if (old != null) {
                if (history.get(old.id) == null) return "Cancelled: that paste was already replaced.".also { discard(order.file) }
                previous = withContext(Dispatchers.IO) { runCatching { load(saved(old.id)) }.getOrNull() }
                    ?: return "Cancelled: the original schematic is missing or unreadable.".also { discard(order.file) }
            }
            if (order.cost > 0) {
                Gems.charge(order.player, "discord-paste:${order.chunks}", order.cost)
                    ?: return "Cancelled: not enough gems.".also { discard(order.file) }
                journal("charged", order, by)
            }
            val done = CompletableDeferred<Result<Unit>>()
            val queued =
                WorldEditExecutor.submit {
                    done.complete(runCatching { paste(clipboard, order, previous) })
                }
            val result = if (queued) done.await() else Result.failure(IllegalStateException("WorldEdit is shutting down"))
            if (result.isFailure) {
                if (order.cost > 0) {
                    Gems.refund(order.player, order.cost)
                    journal("refunded", order, by)
                } else {
                    journal("repaste failed", order, by)
                }
                System.err.println("[Discord] Paste failed (${result.exceptionOrNull()?.javaClass?.simpleName})")
                discard(order.file)
                return if (order.cost > 0) "Paste failed; ${order.cost} gems refunded." else "Repaste failed; nothing was charged."
            }
            journal(if (old != null) "repasted" else "pasted", order, by)
            runCatching {
                Files.createDirectories(saved(order.id).parent)
                Files.move(order.file, saved(order.id))
            }
            runCatching { history.replace(old?.id, PasteHistory.Entry(order.id, order.player, order.x, order.y, order.z, order.town, order.allowance, System.currentTimeMillis())) }
                .onFailure { System.err.println("[Discord] Could not record paste ${order.id} in history (${it.javaClass.simpleName}); it cannot be repasted") }
            return if (old != null) {
                "Repasted over the old build at ${order.x} ${order.y} ${order.z} in ${order.town} (${order.chunks} chunk(s)). No charge."
            } else {
                "Pasted ${order.chunks} chunk(s) at ${order.x} ${order.y} ${order.z} in ${order.town}. Charged ${order.cost} gems."
            }
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            throw error
        }
    }

    /** Writes only inside paid-for chunks, so air in an unpaid chunk of the bounding box never clears anything. */
    private fun paidChunks(
        clipboard: Clipboard,
        order: Order,
    ): Mask {
        val origin = clipboard.origin
        return object : AbstractMask() {
            override fun test(vector: BlockVector3): Boolean {
                val cx = (order.x + vector.x() - origin.x()) shr 4
                val cz = (order.z + vector.z() - origin.z()) shr 4
                return ((cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)) in order.allowance
            }
        }
    }

    private fun saved(id: String): Path = Path.of("worldedit", "schematics", "$id.schem")

    private fun paste(
        clipboard: Clipboard,
        order: Order,
        previous: Clipboard?,
    ) {
        val session =
            WorldEdit
                .getInstance()
                .newEditSessionBuilder()
                .world(MinestomAdapter.asWorld(world))
                .actor(MinestomConsole)
                .build()
        session.use {
            val old = order.replaces
            if (previous != null && old != null) {
                // Clear only what the old paste placed, so anything else the player built there stays.
                val o = previous.origin
                for (pos in previous.region) {
                    if (previous.getBlock(pos).blockType.material.isAir) continue
                    it.setBlock(BlockVector3.at(old.x + pos.x() - o.x(), old.y + pos.y() - o.y(), old.z + pos.z() - o.z()), BlockTypes.AIR!!.defaultState)
                }
            }
            Operations.complete(
                ClipboardHolder(clipboard)
                    .createPaste(it)
                    .to(BlockVector3.at(order.x, order.y, order.z))
                    .ignoreAirBlocks(previous != null)
                    .maskSource(paidChunks(clipboard, order))
                    .build(),
            )
        }
    }

    /** Game-thread check: footprint must lie fully inside the buyer's own town, in world height, and be affordable. */
    private fun check(
        order: Order,
        box: Box,
    ): String {
        val town = Resident.fromUuid(order.player)?.town ?: error("You are not in a town.")
        val dimension = world.cachedDimensionType
        if (box.minY < dimension.minY() || box.maxY >= dimension.minY() + dimension.height()) error("The paste is outside world height.")
        // Only chunks that hold blocks matter; the paste mask never writes anywhere else.
        for (packed in order.allowance) {
            val cx = (packed shr 32).toInt()
            val cz = packed.toInt()
            if (Territory.fromBlock(cx shl 4, cz shl 4)?.town !== town) error("The paste must be entirely inside your own town (${town.name}).")
        }
        if (order.cost > 0) {
            val balance = Gems.balance(order.player) ?: error("No gem balance found; join the server once first.")
            if (balance < order.cost) error("Not enough gems: need ${order.cost}, have $balance.")
        }
        return town.name
    }

    class Box(
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val maxX: Int,
        val maxY: Int,
        val maxZ: Int,
    ) {
        val width get() = maxX - minX + 1
        val height get() = maxY - minY + 1
        val length get() = maxZ - minZ + 1
    }

    private fun box(
        clipboard: Clipboard,
        x: Int,
        y: Int,
        z: Int,
    ): Box {
        val min = clipboard.region.minimumPoint
        val max = clipboard.region.maximumPoint
        val origin = clipboard.origin
        return Box(
            x + min.x() - origin.x(), y + min.y() - origin.y(), z + min.z() - origin.z(),
            x + max.x() - origin.x(), y + max.y() - origin.y(), z + max.z() - origin.z(),
        )
    }

    /** One pass: rejects ores, and counts the world chunks holding at least one non-air block. */
    private fun scan(
        clipboard: Clipboard,
        x: Int,
        z: Int,
    ): Result<Set<Long>> {
        val origin = clipboard.origin
        val touched = HashSet<Long>()
        for (pos in clipboard.region) {
            val type = clipboard.getBlock(pos).blockType
            if (type.material.isAir) continue
            if (isOre(type.id())) return Result.failure(IllegalArgumentException("Schematics containing ores or mineral blocks are not allowed (found ${type.id()})."))
            val cx = (x + pos.x() - origin.x()) shr 4
            val cz = (z + pos.z() - origin.z()) shr 4
            touched += (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)
        }
        return if (touched.isEmpty()) Result.failure(IllegalArgumentException("The schematic contains no blocks.")) else Result.success(touched)
    }

    /** A small gzip can unpack to gigabytes; measure it without keeping it before WorldEdit reads it into memory. */
    private fun inflatedWithinLimit(file: Path): Boolean =
        try {
            GZIPInputStream(Files.newInputStream(file)).use { stream ->
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_INFLATED) return false
                }
                true
            }
        } catch (_: IOException) {
            false
        }

    private fun load(file: Path): Clipboard? {
        val format = ClipboardFormats.findByFile(file.toFile()) ?: return null
        return Files.newInputStream(file).use { format.getReader(it).read() }
    }

    private fun discard(file: Path) {
        runCatching { Files.deleteIfExists(file) }
    }

    private fun journal(
        event: String,
        order: Order,
        by: String? = null,
    ) {
        // Append-only so a crash between charge and paste is visible to staff.
        log?.post(event, order, by)
        runCatching {
            Files.createDirectories(dir)
            Files.writeString(
                dir.resolve("journal.log"),
                "${System.currentTimeMillis()} $event ${order.id} ${order.player} ${order.cost} ${order.x},${order.y},${order.z}${by?.let { " by $it" }.orEmpty()}\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    private fun fail(message: String): Result<Order> = Result.failure(IllegalArgumentException(message))

    companion object {
        const val COST_PER_CHUNK = 50L
        const val MAX_BYTES = 5_000_000
        const val MAX_INFLATED = 100_000_000L
        const val MAX_SIDE = 256
        const val MAX_HEIGHT = 384

        private val MINERAL_BLOCKS =
            setOf(
                "coal_block", "iron_block", "gold_block", "diamond_block", "emerald_block",
                "lapis_block", "redstone_block", "netherite_block", "copper_block",
                "exposed_copper", "weathered_copper", "oxidized_copper", "ancient_debris",
                "bedrock", "obsidian", "crying_obsidian",
            )

        /** Ores, raw ore and storage blocks of mined materials, bedrock and obsidian: a paste must not hand out these. */
        fun isOre(id: String): Boolean {
            val name = id.substringAfter(':').removePrefix("waxed_")
            return name.endsWith("_ore") || name.startsWith("raw_") && name.endsWith("_block") || name in MINERAL_BLOCKS
        }
    }
}

/** Posts audit lines to a Discord webhook; the URL is a secret and is never logged. */
internal class PasteLog(
    private val url: String,
    private val http: HttpClient,
    private val scope: CoroutineScope,
) {
    fun post(
        event: String,
        order: PasteOrders.Order,
        by: String? = null,
    ) {
        val color =
            when (event) {
                "pasted", "repasted" -> 0x57F287
                "charged" -> 0x5865F2
                "rejected", "refunded", "repaste failed" -> 0xED4245
                else -> 0xF0B232
            }
        fun field(
            name: String,
            value: String,
        ) = buildJsonObject {
            put("name", name)
            put("value", value)
            put("inline", true)
        }
        scope.launch {
            try {
                http.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("allowed_mentions", buildJsonObject { put("parse", JsonArray(emptyList())) })
                            put(
                                "embeds",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("title", event.replaceFirstChar { it.uppercase() })
                                            put("color", color)
                                            put(
                                                "fields",
                                                JsonArray(
                                                    listOf(
                                                        field("Player", "<@${order.discordId}>"),
                                                        field("Town", order.town.ifEmpty { "-" }),
                                                        field("Origin", "`${order.x} ${order.y} ${order.z}`"),
                                                        field("Chunks", "${order.chunks}"),
                                                        field("Cost", if (order.cost > 0) "${order.cost} gems" else "Free"),
                                                        field("Minecraft", "`${order.player}`"),
                                                    ) + listOfNotNull(
                                                        by?.let { field("Staff", "<@$it>") },
                                                    ),
                                                ),
                                            )
                                            put("footer", buildJsonObject { put("text", "Order ${order.id}") })
                                        },
                                    ),
                                ),
                            )
                        }.toString(),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                System.err.println("[Discord] Paste log post failed (${error.javaClass.simpleName})")
            }
        }
    }
}

