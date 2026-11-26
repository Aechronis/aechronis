package net.aechronis.discord

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.function.mask.AbstractMask
import com.sk89q.worldedit.function.mask.BlockTypeMask
import com.sk89q.worldedit.function.mask.Mask
import com.sk89q.worldedit.function.mask.Masks
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.math.transform.AffineTransform
import com.sk89q.worldedit.session.ClipboardHolder
import com.sk89q.worldedit.world.block.BlockTypes
import dev.kord.common.entity.Snowflake
import io.github.openminigameserver.worldedit.platform.actors.MinestomConsole
import io.github.openminigameserver.worldedit.platform.adapters.MinestomAdapter
import io.github.openminigameserver.worldedit.platform.misc.WorldEditExecutor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.aechronis.economy.Economy
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.minestom.server.instance.InstanceContainer
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/** Gem-priced schematic pastes bought through Discord. Orders live in memory; a restart just means re-running /paste. */
internal class PasteOrders(
    private val links: AccountLinks,
    private val world: InstanceContainer,
    /** Where staff review and approve; anyone who can see it can decide. */
    val staffChannel: Snowflake,
    /** Public base of the web chunk picker; a draft's link is this plus its id. */
    val webUrl: String,
    /** Local port the picker listens on, behind the public proxy. */
    val webPort: Int,
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
        /** Chunks this paste writes. */
        val chunks: Int,
        /** Chunks bought now; the rest were already paid for by an earlier paste. */
        val newChunks: Int,
        val cost: Long,
        val town: String,
        val file: Path,
        /** Chunks this paste writes to: the player's selection. */
        val allowance: Set<Long>,
        /** Clockwise quarter turns applied to the schematic. */
        val rotation: Int,
    )

    /** Where a draft lands: everything that depends on the origin the player typed on the web picker. */
    class Placement(
        val x: Int,
        val y: Int,
        val z: Int,
        val touched: Set<Long>,
        val box: Box,
        val map: ChunkMap,
        /** Chunks outside the player's own town; they can't be pasted. */
        val blocked: Set<Long>,
        /** Node (territory) of every chunk in view, row-major over the map grid; -1 for none. Indexes [nodeNames]. */
        val nodes: IntArray,
        val nodeNames: List<String>,
        /** Node under the origin block. */
        val node: String?,
        val rotation: Int,
    )

    /** A validated upload waiting for the player to set the origin and pick chunks on the web picker. */
    class Draft(
        /** Random, and the only secret in the picker link. */
        val id: String,
        val discordId: String,
        val player: UUID,
        val file: Path,
        /** Every chunk the player already paid for in earlier pastes; pasting there again is free. */
        val paid: Set<Long>,
        val clipboard: Clipboard,
        val footprint: Footprint,
        /** Where /paste ran, so the staff decision is reported back there. */
        val channel: Snowflake,
        val created: Long = System.currentTimeMillis(),
    ) {
        /** The footprint at 0, 90, 180 and 270 degrees clockwise. */
        val rotations: List<Footprint> by lazy { (0..3).map { footprint.rotated(it) } }

        @Volatile var placement: Placement? = null
        internal val placing = AtomicBoolean()
    }

    private val drafts = ConcurrentHashMap<String, Draft>()

    /** Where a submitted order stands, for the picker page; kept a day after the last change. */
    class Status(
        val player: UUID,
        val state: String,
        val message: String,
        val time: Long = System.currentTimeMillis(),
    )

    private val statuses = ConcurrentHashMap<String, Status>()

    fun status(id: String): Status? = statuses[id]

    private fun status(
        id: String,
        player: UUID,
        state: String,
        message: String,
    ) {
        val now = System.currentTimeMillis()
        statuses.entries.removeIf { now - it.value.time > STATUS_MILLIS }
        statuses[id] = Status(player, state, message)
    }

    /** A read-only map of everything a player has paid for, from /paste without a schematic. */
    class Land(
        val id: String,
        val player: UUID,
        render: () -> ChunkMap,
        val nodes: IntArray,
        val nodeNames: List<String>,
        val paid: Set<Long>,
        val created: Long = System.currentTimeMillis(),
    ) {
        /** Drawn when the page first asks, off the Discord command's time limit. */
        val map: ChunkMap by lazy(render)
    }

    private val lands = ConcurrentHashMap<String, Land>()

    fun land(id: String): Land? = lands[id]?.takeIf { System.currentTimeMillis() - it.created < DRAFT_MILLIS }

    suspend fun prepareLand(discordId: String): Result<Land> {
        val player =
            links.player(discordId) ?: return fail("Link your account first: /discord link in Minecraft, then /link code:<code> here.")
        val paid = history.paid(player)
        if (paid.isEmpty()) return fail("You haven't paid for any chunks yet. Attach a .schem to /paste to buy some.")
        val minCx = paid.minOf { ChunkMap.cx(it) }
        val maxCx = paid.maxOf { ChunkMap.cx(it) }
        val minCz = paid.minOf { ChunkMap.cz(it) }
        val maxCz = paid.maxOf { ChunkMap.cz(it) }
        // ponytail: one window of at most MAX_LAND chunks a side around the middle; paid chunks far apart fall off the map.
        val cols = minOf(maxCx - minCx + 1 + 2 * ChunkMap.MARGIN, MAX_LAND)
        val rows = minOf(maxCz - minCz + 1 + 2 * ChunkMap.MARGIN, MAX_LAND)
        val startCx = (minCx + maxCx) / 2 - cols / 2
        val startCz = (minCz + maxCz) / 2 - rows / 2
        val area = (0 until rows).flatMap { row -> (0 until cols).map { col -> ChunkMap.pack(startCx + col, startCz + row) } }
        val owners =
            try {
                onGameThread { owners(player, area, (startCx + cols / 2) shl 4, (startCz + rows / 2) shl 4) }
            } catch (rejection: IllegalStateException) {
                return Result.failure(rejection)
            }
        val render = {
            ChunkMap.render(world, intArrayOf(startCx, startCz, cols, rows), null, 0, 0, 0, emptySet(), owners.own, owners.claimed)
        }
        val now = System.currentTimeMillis()
        lands.values.removeIf { it.player == player || now - it.created >= DRAFT_MILLIS }
        val land = Land(UUID.randomUUID().toString(), player, render, owners.nodes, owners.nodeNames, paid)
        lands[land.id] = land
        return Result.success(land)
    }

    fun draft(id: String): Draft? = drafts[id]?.takeIf { System.currentTimeMillis() - it.created < DRAFT_MILLIS }

    fun cancel(id: String) {
        drafts.remove(id)?.let { discard(it.file) }
    }

    private fun purgeDrafts() {
        val now = System.currentTimeMillis()
        drafts.values.filter { now - it.created >= DRAFT_MILLIS }.forEach { cancel(it.id) }
    }

    private val pending = ConcurrentHashMap<String, Order>()

    /** Ticket channel of each open order, so the outcome can be reported back there. */
    val ticketOf = ConcurrentHashMap<String, Snowflake>()

    /** Shulkr viewer link of each open order, kept on the staff post after a decision. */
    val viewerOf = ConcurrentHashMap<String, String>()

    /** Optional audit trail, kept in a different Discord server through a webhook. */
    var log: PasteLog? = null

    /** Downloads and validates an upload into a draft for the web picker. Returns the draft or a user-facing rejection. */
    suspend fun prepare(
        http: HttpClient,
        discordId: String,
        filename: String,
        size: Int,
        url: String,
        channel: Snowflake,
    ): Result<Draft> {
        val player =
            links.player(discordId) ?: return fail("Link your account first: /discord link in Minecraft, then /link code:<code> here.")
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
            // WorldEdit allocates for the declared volume, so judge the header before loading.
            val dims = withContext(Dispatchers.IO) { declaredSize(file) }
            if (dims != null && (dims[0] > MAX_SIDE || dims[2] > MAX_SIDE || dims[1] > MAX_HEIGHT)) {
                discard(file)
                return fail("Too large: at most $MAX_SIDE x $MAX_HEIGHT x $MAX_SIDE blocks.")
            }
            val clipboard =
                load(file) ?: run {
                    discard(file)
                    return fail("Could not read that file as a .schem schematic.")
                }
            // Size does not depend on the origin, so judge it now rather than on every placement.
            val box = box(clipboard, 0, 0, 0)
            if (box.width > MAX_SIDE || box.length > MAX_SIDE || box.height > MAX_HEIGHT) {
                discard(file)
                return fail("Too large: at most $MAX_SIDE x $MAX_HEIGHT x $MAX_SIDE blocks.")
            }
            // Ores and empty schematics are rejected here, so the player hears about it in Discord, not on the page.
            val footprint =
                withContext(Dispatchers.Default) { ChunkMap.footprint(clipboard) }.getOrElse {
                    discard(file)
                    return Result.failure(it)
                }
            val draft = Draft(id, discordId, player, file, history.paid(player), clipboard, footprint, channel)
            // One open draft per player; a new upload replaces the old one.
            purgeDrafts()
            drafts.values.filter { it.player == player }.forEach { cancel(it.id) }
            drafts[id] = draft
            return Result.success(draft)
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            discard(file)
            throw error
        } catch (error: Exception) {
            discard(file)
            System.err.println("[Discord] Paste prepare failed (${error.javaClass.simpleName})")
            return fail("Could not process that schematic.")
        }
    }

    /** Lays the draft out at an origin: chunk grid, render and which chunks fall outside the player's town. */
    suspend fun place(
        id: String,
        x: Int,
        y: Int,
        z: Int,
        rotation: Int,
    ): Result<Placement> {
        val draft = draft(id) ?: return fail(EXPIRED)
        // Each placement scans and renders the whole schematic; one at a time per draft.
        if (!draft.placing.compareAndSet(false, true)) return fail("Still working on the last placement; try again in a moment.")
        try {
            val footprint = draft.rotations[Math.floorMod(rotation, 4)]
            val bounds = ChunkMap.bounds(draft.rotations, x, z)
            val touched = footprint.chunks(x, z)
            val area = ChunkMap.area(bounds)
            val owners =
                try {
                    onGameThread { owners(draft.player, area, x, z) }
                } catch (rejection: IllegalStateException) {
                    return Result.failure(rejection)
                }
            val blocked = touched - owners.own
            val map =
                withContext(Dispatchers.IO) {
                    ChunkMap.render(world, bounds, footprint, x, y, z, touched, owners.own, owners.claimed)
                }
            val placement =
                Placement(
                    x,
                    y,
                    z,
                    touched,
                    box(draft.clipboard, x, y, z),
                    map,
                    blocked,
                    owners.nodes,
                    owners.nodeNames,
                    owners.node,
                    rotation,
                )
            draft.placement = placement
            return Result.success(placement)
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            throw error
        } catch (error: Exception) {
            System.err.println("[Discord] Paste placement failed (${error.javaClass.simpleName})")
            return fail("Could not place the schematic there.")
        } finally {
            draft.placing.set(false)
        }
    }

    /**
     * Turns a draft and its chunk selection into a pending order; the draft stays open if a check fails.
     * The origin is sent back with the selection, so a placement changed in another tab can't be confirmed blind.
     */
    suspend fun confirm(
        id: String,
        x: Int,
        y: Int,
        z: Int,
        rotation: Int,
        selected: Set<Long>,
    ): Result<Order> {
        val draft = draft(id) ?: return fail(EXPIRED)
        val placement = draft.placement ?: return fail("Set the coordinates first.")
        if (placement.x != x || placement.y != y || placement.z != z || placement.rotation != rotation) {
            return fail("The placement changed; press Load and pick again.")
        }
        val allowance = selected intersect placement.touched
        val added = allowance - draft.paid
        if (allowance.isEmpty()) return fail("Select at least one chunk first.")
        val newChunks = added.size
        val order =
            Order(
                draft.id,
                draft.discordId,
                draft.player,
                placement.x,
                placement.y,
                placement.z,
                allowance.size,
                newChunks,
                newChunks * COST_PER_CHUNK,
                "",
                draft.file,
                allowance,
                rotation,
            )
        val town =
            try {
                onGameThread { check(order, placement.box, placement.map) }
            } catch (rejection: IllegalStateException) {
                return Result.failure(rejection)
            }
        // A double submit must not become two orders.
        if (!drafts.remove(draft.id, draft)) return fail(EXPIRED)
        val ready =
            Order(
                order.id,
                order.discordId,
                order.player,
                order.x,
                order.y,
                order.z,
                order.chunks,
                order.newChunks,
                order.cost,
                town,
                order.file,
                order.allowance,
                order.rotation,
            )
        // One open order per player; a newer request replaces the older one.
        pending.values.filter { it.player == draft.player }.forEach {
            pending.remove(it.id)?.let { old ->
                discard(old.file)
                status(old.id, old.player, "rejected", "Replaced by a newer request.")
            }
        }
        pending[draft.id] = ready
        status(ready.id, ready.player, "pending", "Waiting for staff review. Gems are charged on approval.")
        journal("requested", ready)
        return Result.success(ready)
    }

    fun reject(
        id: String,
        by: String,
    ): Boolean =
        pending.remove(id)?.also {
            discard(it.file)
            journal("rejected", it, by)
            // A withdrawn post already told the page why.
            if (by == "system") statuses.remove(id) else status(id, it.player, "rejected", "Rejected by staff. Nothing was charged.")
        } != null

    /** Charges and pastes. Returns a message for the ticket. Single use: the order is claimed atomically. */
    suspend fun approve(
        id: String,
        by: String,
    ): String {
        val order = pending.remove(id) ?: return "That order was already handled or expired. Ask the player to run /paste again."
        return execute(order, by).also {
            if (it.startsWith("Pasted")) status(id, order.player, "approved", it) else status(id, order.player, "failed", it)
        }
    }

    private suspend fun execute(
        order: Order,
        by: String,
    ): String {
        try {
            if (links.player(order.discordId) !=
                order.player
            ) {
                return "The player's account was unlinked. Order cancelled.".also { discard(order.file) }
            }
            val clipboard =
                withContext(Dispatchers.IO) { load(order.file) }
                    ?: return "Schematic file is unreadable. Order cancelled.".also { discard(order.file) }
            val box = box(clipboard, order.x, order.y, order.z)
            try {
                onGameThread { check(order, box) }
            } catch (rejection: IllegalStateException) {
                discard(order.file)
                return "Cancelled: ${rejection.message}"
            }
            if (order.cost > 0) {
                Economy.charge(order.player, "discord-paste:${order.chunks}", order.cost)
                    ?: return "Cancelled: not enough gems.".also { discard(order.file) }
                journal("charged", order, by)
            }
            val done = CompletableDeferred<Result<Unit>>()
            val queued =
                WorldEditExecutor.submit {
                    done.complete(runCatching { paste(clipboard, order) })
                }
            val result = if (queued) done.await() else Result.failure(IllegalStateException("WorldEdit is shutting down"))
            if (result.isFailure) {
                if (order.cost > 0) {
                    Economy.refund(order.player, order.cost)
                    journal("refunded", order, by)
                } else {
                    journal("paste failed", order, by)
                }
                System.err.println("[Discord] Paste failed (${result.exceptionOrNull()?.javaClass?.simpleName})")
                discard(order.file)
                return if (order.cost > 0) "Paste failed; ${order.cost} gems refunded." else "Paste failed; nothing was charged."
            }
            journal("pasted", order, by)
            runCatching {
                Files.createDirectories(saved(order.id).parent)
                Files.move(order.file, saved(order.id))
            }
            runCatching {
                history.add(
                    PasteHistory.Entry(
                        order.id,
                        order.player,
                        order.x,
                        order.y,
                        order.z,
                        order.town,
                        order.allowance,
                        System.currentTimeMillis(),
                    ),
                )
            }.onFailure {
                System.err.println(
                    "[Discord] Could not record paste ${order.id} in history (${it.javaClass.simpleName}); its chunks are not marked paid",
                )
            }
            return "Pasted ${order.chunks} chunk(s) at ${order.x} ${order.y} ${order.z} in ${order.town} " +
                "(${order.newChunks} new). Charged ${order.cost} gems."
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
                // Source positions, so turn them the way the paste does: (x, z) to (-z, x) per quarter turn.
                var dx = vector.x() - origin.x()
                var dz = vector.z() - origin.z()
                repeat(order.rotation) {
                    val t = dx
                    dx = -dz
                    dz = t
                }
                val cx = (order.x + dx) shr 4
                val cz = (order.z + dz) shr 4
                return ((cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)) in order.allowance
            }
        }
    }

    private fun saved(id: String): Path = Path.of("worldedit", "schematics", "$id.schem")

    private fun paste(
        clipboard: Clipboard,
        order: Order,
    ) {
        val session =
            WorldEdit
                .getInstance()
                .newEditSessionBuilder()
                .world(MinestomAdapter.asWorld(world))
                .actor(MinestomConsole)
                .build()
        session.use {
            // Whatever is already standing there as a barrel stays: neither the paste nor the clearing may touch it.
            it.mask = Masks.negate(BlockTypeMask(it, listOf(BlockTypes.BARREL!!)))
            Operations.complete(
                ClipboardHolder(clipboard)
                    .apply { transform = AffineTransform().rotateY(-90.0 * order.rotation) }
                    .createPaste(it)
                    .to(BlockVector3.at(order.x, order.y, order.z))
                    .ignoreAirBlocks(false)
                    .maskSource(paidChunks(clipboard, order))
                    .build(),
            )
        }
    }

    private class Owners(
        val own: Set<Long>,
        val claimed: Set<Long>,
        val nodes: IntArray,
        val nodeNames: List<String>,
        val node: String?,
    )

    /** Game-thread: which of these chunks belong to the player's own town, to other towns, and to which node. */
    private fun owners(
        player: UUID,
        chunks: List<Long>,
        x: Int,
        z: Int,
    ): Owners {
        val town = Resident.fromUuid(player)?.town ?: error("You are not in a town.")
        val own = HashSet<Long>()
        val claimed = HashSet<Long>()
        val index = LinkedHashMap<Territory, Int>()
        val nodes =
            IntArray(chunks.size) { i ->
                val chunk = chunks[i]
                val territory = Territory.fromBlock(ChunkMap.cx(chunk) shl 4, ChunkMap.cz(chunk) shl 4) ?: return@IntArray -1
                val owner = territory.town
                if (owner === town) {
                    own += chunk
                } else if (owner != null) {
                    claimed += chunk
                }
                index.getOrPut(territory) { index.size }
            }
        val names = index.keys.map { it.name.ifEmpty { "Node ${it.id}" } }
        return Owners(own, claimed, nodes, names, Territory.fromBlock(x, z)?.let { it.name.ifEmpty { "Node ${it.id}" } })
    }

    /** What the picker's side panel shows about the player. */
    class Profile(
        val name: String?,
        val town: String?,
        val nation: String?,
        val balance: Long?,
    )

    fun paidCount(player: UUID): Int = history.paid(player).size

    suspend fun profile(player: UUID): Profile =
        onGameThread {
            val resident = Resident.fromUuid(player)
            Profile(resident?.name, resident?.town?.name, resident?.town?.nation?.name, Economy.balance(player))
        }

    /** Game-thread check: footprint must lie fully inside the buyer's own town, in world height, and be affordable. */
    private fun check(
        order: Order,
        box: Box,
        map: ChunkMap? = null,
    ): String {
        val town = Resident.fromUuid(order.player)?.town ?: error("You are not in a town.")
        val dimension = world.cachedDimensionType
        if (box.minY < dimension.minY() || box.maxY >= dimension.minY() + dimension.height()) error("The paste is outside world height.")
        // Only chunks that hold blocks matter; the paste mask never writes anywhere else.
        for (packed in order.allowance) {
            val cx = (packed shr 32).toInt()
            val cz = packed.toInt()
            if (Territory.fromBlock(cx shl 4, cz shl 4)?.town !==
                town
            ) {
                error("Chunk ${map?.label(packed) ?: "$cx,$cz"} is outside your own town (${town.name}); deselect it.")
            }
        }
        if (order.cost > 0) {
            val balance = Economy.balance(order.player) ?: error("No gem balance found; join the server once first.")
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
            x + min.x() - origin.x(),
            y + min.y() - origin.y(),
            z + min.z() - origin.z(),
            x + max.x() - origin.x(),
            y + max.y() - origin.y(),
            z + max.z() - origin.z(),
        )
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

    /**
     * Uploaded schematics lose their block-entity data and entities: Storage reads barrel
     * contents from the block's `Items` NBT, so a schematic could otherwise carry free loot.
     */
    private fun load(file: Path): Clipboard? {
        val format = ClipboardFormats.findByFile(file.toFile()) ?: return null
        val clipboard = Files.newInputStream(file).use { format.getReader(it).read() }
        for (pos in clipboard.region) {
            val block = clipboard.getFullBlock(pos)
            if (block.nbt != null) clipboard.setBlock(pos, block.toImmutableState())
        }
        clipboard.entities.toList().forEach { it.remove() }
        return clipboard
    }

    /** Width, height, length from the schematic header without loading it, or null if unreadable. */
    private fun declaredSize(file: Path): IntArray? =
        try {
            DataInputStream(BufferedInputStream(GZIPInputStream(Files.newInputStream(file)))).use { input ->
                val dims = IntArray(3) { -1 }
                if (input.readByte().toInt() != 10) return null
                input.readUTF()
                findDims(input, dims, 0)
                if (dims.any { it < 0 }) null else dims
            }
        } catch (_: IOException) {
            null
        }

    private fun findDims(
        input: DataInputStream,
        dims: IntArray,
        depth: Int,
    ) {
        while (dims.any { it < 0 }) {
            val type = input.readByte().toInt()
            if (type == 0) return
            val index = listOf("Width", "Height", "Length").indexOf(input.readUTF())
            when {
                type == 2 && index >= 0 -> dims[index] = input.readShort().toInt() and 0xffff
                type == 10 && depth < 2 -> findDims(input, dims, depth + 1)
                else -> skipPayload(input, type)
            }
        }
    }

    private fun skipPayload(
        input: DataInputStream,
        type: Int,
    ) {
        fun skip(bytes: Long) {
            if (bytes < 0) throw IOException("Bad NBT length")
            input.skipNBytes(bytes)
        }
        when (type) {
            1 -> skip(1)
            2 -> skip(2)
            3, 5 -> skip(4)
            4, 6 -> skip(8)
            7 -> skip(input.readInt().toLong())
            8 -> skip(input.readUnsignedShort().toLong())
            9 -> {
                val element = input.readByte().toInt()
                repeat(input.readInt()) { skipPayload(input, element) }
            }
            10 -> {
                while (true) {
                    val inner = input.readByte().toInt()
                    if (inner == 0) break
                    skip(input.readUnsignedShort().toLong())
                    skipPayload(input, inner)
                }
            }
            11 -> skip(input.readInt() * 4L)
            12 -> skip(input.readInt() * 8L)
            else -> throw IOException("Bad NBT tag $type")
        }
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
                "${System.currentTimeMillis()} $event ${order.id} ${order.player} ${order.cost} ${order.x},${order.y},${order.z}${by?.let {
                    " by $it"
                }.orEmpty()}\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    private fun <T> fail(message: String): Result<T> = Result.failure(IllegalArgumentException(message))

    companion object {
        const val COST_PER_CHUNK = 75L
        const val DRAFT_MILLIS = 15 * 60_000L
        const val STATUS_MILLIS = 24 * 60 * 60_000L
        const val MAX_LAND = 32
        const val EXPIRED = "This picker link expired. Run the command in Discord again."
        const val MAX_BYTES = 5_000_000
        const val MAX_INFLATED = 100_000_000L
        const val MAX_SIDE = 256
        const val MAX_HEIGHT = 384

        private val MINERAL_BLOCKS =
            setOf(
                "coal_block",
                "iron_block",
                "gold_block",
                "diamond_block",
                "emerald_block",
                "lapis_block",
                "redstone_block",
                "netherite_block",
                "ancient_debris",
                "bedrock",
                "obsidian",
                "crying_obsidian",
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
                "pasted" -> 0x57F287
                "charged" -> 0x5865F2
                "rejected", "refunded", "paste failed" -> 0xED4245
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
                                                    ) +
                                                        listOfNotNull(
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
