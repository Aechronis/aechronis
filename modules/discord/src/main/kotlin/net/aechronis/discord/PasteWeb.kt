package net.aechronis.discord

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The web chunk picker. Each draft's random id is its link's only secret, so anyone holding the link
 * acts as that player; Discord hands it out ephemerally. Listens on loopback behind the public proxy.
 */
internal class PasteWeb private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
    private val pastes: PasteOrders,
    /** Posts the order for staff; throwing means the post failed and the order is withdrawn. */
    private val submit: suspend (PasteOrders.Order, PasteOrders.Draft, PasteOrders.Placement) -> Unit,
) : AutoCloseable {
    private val page = PasteWeb::class.java.getResourceAsStream("/paste/index.html")!!.use { it.readBytes() }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            try {
                // /paste/<id>[/<action>]
                val parts =
                    exchange.requestURI.path
                        .removePrefix("/paste/")
                        .split('/')
                val id = parts[0]
                val action = parts.getOrNull(1).orEmpty()
                val draft = pastes.draft(id)
                val land = if (draft == null) pastes.land(id) else null
                val get = exchange.requestMethod == "GET"
                when {
                    parts.size > 2 -> send(exchange, 404, "text/plain", "Not found".toByteArray())
                    action == "" && get -> send(exchange, 200, "text/html; charset=utf-8", page)
                    action == "status" && get ->
                        json(
                            exchange,
                            pastes.status(id)?.let { runBlocking { status(it) } } ?: failure(PasteOrders.EXPIRED),
                        )
                    land != null && action == "state" && get -> json(exchange, runBlocking { land(land) })
                    land != null && action == "map.png" && get -> send(exchange, 200, "image/png", land.map.web)
                    // A submitted order's link keeps showing where it stands.
                    draft == null && action == "state" && get ->
                        json(exchange, pastes.status(id)?.let { runBlocking { status(it) } } ?: failure(PasteOrders.EXPIRED))
                    draft == null -> json(exchange, failure(PasteOrders.EXPIRED))
                    action == "state" && exchange.requestMethod == "GET" -> json(exchange, runBlocking { state(draft, draft.placement) })
                    action == "map.png" && exchange.requestMethod == "GET" -> {
                        val map = draft.placement?.map ?: return send(exchange, 404, "text/plain", "No placement".toByteArray())
                        send(exchange, 200, "image/png", map.web)
                    }
                    action == "place" && exchange.requestMethod == "POST" -> {
                        val form = form(exchange) ?: return json(exchange, failure("Request too large."))
                        val (x, y, z) =
                            coords(form)
                                ?: return json(exchange, failure("Coordinates must be whole numbers within the world border."))
                        val result = runBlocking { pastes.place(id, x, y, z, rotation(form)) }
                        json(exchange, runBlocking { result.fold({ state(draft, it) }, { failure(it.message) }) })
                    }
                    action == "submit" && exchange.requestMethod == "POST" -> {
                        val form = form(exchange) ?: return json(exchange, failure("Request too large."))
                        val (x, y, z) = coords(form) ?: return json(exchange, failure("Coordinates are missing."))
                        val chunks =
                            form["chunks"]
                                .orEmpty()
                                .split(',')
                                .mapNotNull { it.toLongOrNull() }
                                .toSet()
                        json(exchange, runBlocking { submit(draft, x, y, z, rotation(form), chunks) })
                    }
                    else -> send(exchange, 405, "text/plain", "Method not allowed".toByteArray())
                }
            } catch (error: Exception) {
                System.err.println("[Discord] Paste web request failed (${error.javaClass.simpleName})")
                runCatching { send(exchange, 500, "text/plain", "Server error".toByteArray()) }
            }
        }
    }

    private suspend fun submit(
        draft: PasteOrders.Draft,
        x: Int,
        y: Int,
        z: Int,
        rotation: Int,
        chunks: Set<Long>,
    ): JsonObject {
        val order = pastes.confirm(draft.id, x, y, z, rotation, chunks).getOrElse { return failure(it.message) }
        try {
            // Read after confirming: confirm checked this very placement, and the draft is closed to new ones.
            submit(order, draft, draft.placement!!)
        } catch (error: CancellationException) {
            pastes.reject(order.id, "system")
            throw error
        } catch (error: Exception) {
            pastes.reject(order.id, "system")
            System.err.println("[Discord] Could not post paste for staff (${error.javaClass.simpleName})")
            return failure("Could not post your request for staff; nothing was charged. Run the command in Discord again.")
        }
        return buildJsonObject {
            put(
                "done",
                "Request posted: ${order.chunks} chunk(s), ${order.newChunks} new for ${order.cost} gems. " +
                    "Waiting for staff approval; gems are charged on approval. You can close this page.",
            )
        }
    }

    /** The side panel: who the player is and what they have. */
    private suspend fun JsonObjectBuilder.profile(
        player: UUID,
        paid: Int,
    ) {
        val profile = pastes.profile(player)
        put("costPerChunk", PasteOrders.COST_PER_CHUNK)
        put("uuid", player.toString())
        profile.name?.let { put("name", it) }
        profile.town?.let { put("town", it) }
        profile.nation?.let { put("nation", it) }
        profile.balance?.let { put("balance", it) }
        put("paidTotal", paid)
    }

    /** The map grid: node of every chunk and which ones the player paid for. */
    private fun JsonObjectBuilder.grid(
        map: ChunkMap,
        nodes: IntArray,
        nodeNames: List<String>,
        paid: Set<Long>,
    ) {
        put("cols", map.cols)
        put("rows", map.rows)
        put("nodeNames", JsonArray(nodeNames.map(::JsonPrimitive)))
        put("nodes", JsonArray(nodes.map(::JsonPrimitive)))
        put(
            "paidCells",
            JsonArray(
                (0 until map.rows).flatMap { row ->
                    (0 until map.cols).mapNotNull { col ->
                        (row * map.cols + col).takeIf { map.at(col, row) in paid }?.let(::JsonPrimitive)
                    }
                },
            ),
        )
    }

    private suspend fun status(status: PasteOrders.Status): JsonObject =
        buildJsonObject {
            profile(status.player, pastes.paidCount(status.player))
            put("status", status.state)
            put("message", status.message)
        }

    private suspend fun land(land: PasteOrders.Land): JsonObject =
        buildJsonObject {
            profile(land.player, land.paid.size)
            put("land", true)
            put("expires", land.created + PasteOrders.DRAFT_MILLIS)
            grid(land.map, land.nodes, land.nodeNames, land.paid)
            put("chunks", JsonArray(emptyList()))
        }

    /** Everything the page needs to draw the picker; no placement yet means it only shows the coordinate form. */
    private suspend fun state(
        draft: PasteOrders.Draft,
        placement: PasteOrders.Placement?,
    ): JsonObject =
        buildJsonObject {
            profile(draft.player, draft.paid.size)
            put("expires", draft.created + PasteOrders.DRAFT_MILLIS)
            if (placement == null) return@buildJsonObject
            val map = placement.map
            put("x", placement.x)
            put("y", placement.y)
            put("z", placement.z)
            put("rotation", placement.rotation)
            placement.node?.let { put("node", it) }
            grid(map, placement.nodes, placement.nodeNames, draft.paid)
            put(
                "chunks",
                JsonArray(
                    map.filled.map { chunk ->
                        buildJsonObject {
                            // Packed chunk keys pass 2^53, so they travel as strings.
                            put("id", chunk.toString())
                            put("label", map.label(chunk))
                            put("col", ChunkMap.cx(chunk) - map.minCx)
                            put("row", ChunkMap.cz(chunk) - map.minCz)
                            put("x", ChunkMap.cx(chunk) shl 4)
                            put("z", ChunkMap.cz(chunk) shl 4)
                            put("blocked", chunk in placement.blocked)
                            put("paid", chunk in draft.paid)
                        }
                    },
                ),
            )
        }

    private fun rotation(form: Map<String, String>) = form["rotation"]?.toIntOrNull()?.takeIf { it in 0..3 } ?: 0

    private fun failure(message: String?) = buildJsonObject { put("error", message ?: "Something went wrong.") }

    private fun coords(form: Map<String, String>): Triple<Int, Int, Int>? {
        fun coord(name: String) =
            form[name]
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it in -30_000_000L..30_000_000L }
                ?.toInt()
        return Triple(coord("x") ?: return null, coord("y") ?: return null, coord("z") ?: return null)
    }

    private fun form(exchange: HttpExchange): Map<String, String>? {
        val body = exchange.requestBody.readNBytes(MAX_BODY + 1)
        if (body.size > MAX_BODY) return null
        return String(body, Charsets.UTF_8)
            .split('&')
            .filter { '=' in it }
            .associate {
                URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                    URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
            }
    }

    private fun json(
        exchange: HttpExchange,
        body: JsonObject,
    ) = send(exchange, 200, "application/json", body.toString().toByteArray())

    private fun send(
        exchange: HttpExchange,
        status: Int,
        type: String,
        body: ByteArray,
    ) {
        exchange.responseHeaders.apply {
            set("Content-Type", type)
            set("Cache-Control", "no-store")
            // The link is the credential: never leak it to other sites.
            set("Referrer-Policy", "no-referrer")
            set("X-Content-Type-Options", "nosniff")
            set(
                "Content-Security-Policy",
                "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' https://mc-heads.net; connect-src 'self'",
            )
        }
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.write(body)
    }

    companion object {
        private const val MAX_BODY = 64 * 1024

        fun start(
            port: Int,
            pastes: PasteOrders,
            submit: suspend (PasteOrders.Order, PasteOrders.Draft, PasteOrders.Placement) -> Unit,
        ): PasteWeb {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            val web = PasteWeb(server, executor, pastes, submit)
            server.executor = executor
            server.createContext("/paste/", web::handle)
            server.start()
            return web
        }
    }
}
