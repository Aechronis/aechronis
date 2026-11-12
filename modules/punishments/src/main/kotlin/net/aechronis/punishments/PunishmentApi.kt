package net.aechronis.punishments

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Read-only public JSON API for bans.aechronis.net. Listens on localhost only; nginx proxies to it
 * and sets `X-Real-IP`. Never serializes IP addresses.
 */
internal object PunishmentApi {
    const val BLACKLIST_RULE =
        "All players and groups listed here are permanently banned from Aechronis. " +
            "Having any players from this list in your nation will result in punishment."

    private const val LOOKUPS_PER_MINUTE = 60
    private var server: HttpServer? = null
    private val lookups = ConcurrentHashMap<String, Pair<Long, Int>>()

    fun start(port: Int) {
        if (port <= 0) return
        server =
            try {
                HttpServer.create(InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), port), 0)
            } catch (e: IOException) {
                System.err.println("[Punishments] API could not bind port $port: ${e.message}")
                return
            }
        server?.apply {
            createContext("/api/") { exchange -> handle(exchange) }
            executor = Executors.newVirtualThreadPerTaskExecutor()
            start()
        }
        println("[Punishments] API listening on 127.0.0.1:$port")
    }

    fun stop() {
        server?.stop(0)
        (server?.executor as? ExecutorService)?.shutdownNow()
        server = null
        lookups.clear()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            if (exchange.requestMethod == "OPTIONS") {
                exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET")
                exchange.sendResponseHeaders(204, -1)
                return
            }
            if (exchange.requestMethod != "GET") return send(exchange, 405, error("GET only"))
            val params = query(exchange)
            try {
                when (exchange.requestURI.path.trimEnd('/')) {
                    "/api/blacklist" -> send(exchange, 200, blacklist())
                    "/api/recent" -> send(exchange, 200, recent(params["limit"]?.toIntOrNull() ?: 50))
                    "/api/player" -> {
                        if (!allow(exchange)) return send(exchange, 429, error("Too many lookups, try again in a minute"))
                        val q = params["q"]?.trim().orEmpty()
                        if (q.isEmpty() || q.length > 36) return send(exchange, 400, error("Pass ?q=<name or uuid>"))
                        player(q)?.let { send(exchange, 200, it) } ?: send(exchange, 404, error("Player not found"))
                    }
                    else -> send(exchange, 404, error("Unknown endpoint"))
                }
            } catch (e: Exception) {
                System.err.println("[Punishments] API request failed: $e")
                send(exchange, 500, error("Internal error"))
            }
        }
    }

    private fun blacklist(): JsonObject {
        val repository = Punishments.repository
        return buildJsonObject {
            put("rule", BLACKLIST_RULE)
            put("players", JsonArray(repository.blacklisted().map(::record)))
            putJsonArray("groups") {
                repository.groups().forEach { group ->
                    add(
                        buildJsonObject {
                            put("name", group.name)
                            put("reason", group.reason)
                            put("staffName", group.staffName)
                            put("createdAt", group.createdAt)
                        },
                    )
                }
            }
        }
    }

    private fun recent(limit: Int) =
        buildJsonObject {
            put("records", JsonArray(Punishments.repository.recent(limit.coerceIn(1, 200)).map(::record)))
        }

    private fun player(query: String): JsonObject? {
        val repository = Punishments.repository
        val known = repository.findPlayer(query) ?: return null
        val history = repository.history(known.uuid)
        val active = history.filter { it.active() }
        return buildJsonObject {
            put("uuid", known.uuid.toString())
            put("name", known.name)
            put(
                "discord",
                Punishments.discord(known.uuid)?.let { discord ->
                    buildJsonObject {
                        put("id", discord.id)
                        put("username", discord.username)
                    }
                } ?: JsonNull,
            )
            putJsonObject("status") {
                put("blacklisted", active.any { it.type == Type.BLACKLIST })
                put("banned", active.any { it.type.blocksLogin })
                put("muted", active.any { it.type == Type.MUTE })
            }
            putJsonObject("offences") {
                Category.entries.forEach { put(it.name.lowercase(), repository.offences(known.uuid, it)) }
            }
            put("history", JsonArray(history.map(::record)))
        }
    }

    private fun record(record: Record): JsonElement =
        buildJsonObject {
            put("id", record.id)
            put("offence", record.offence)
            putJsonObject("player") {
                put("uuid", record.uuid.toString())
                put("name", record.name)
            }
            put("type", record.type.name.lowercase())
            put("typeLabel", record.type.label)
            put("category", record.category?.name?.lowercase())
            put("categoryLabel", record.category?.label)
            put("reason", record.reason)
            putJsonObject("staff") {
                put("uuid", record.staffUuid?.toString())
                put("name", record.staffName)
            }
            put("createdAt", record.createdAt)
            put("expiresAt", record.expiresAt)
            put("active", record.active())
            put(
                "lifted",
                if (record.revokedAt == null) {
                    JsonNull
                } else {
                    buildJsonObject {
                        put("at", record.revokedAt)
                        put("by", record.revokedBy)
                        put("reason", record.revokeReason)
                    }
                },
            )
        }

    private fun allow(exchange: HttpExchange): Boolean {
        val ip = exchange.requestHeaders.getFirst("X-Real-IP") ?: exchange.remoteAddress.address.hostAddress
        val minute = System.currentTimeMillis() / 60_000
        if (lookups.size > 10_000) lookups.entries.removeIf { it.value.first != minute }
        val (_, count) = lookups.merge(ip, minute to 1) { old, _ -> if (old.first == minute) minute to old.second + 1 else minute to 1 }!!
        return count <= LOOKUPS_PER_MINUTE
    }

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery
            ?.split('&')
            ?.mapNotNull { part ->
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', "")
                runCatching { URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8) }.getOrNull()
            }?.toMap()
            .orEmpty()

    private fun error(message: String) = buildJsonObject { put("error", message) }

    private fun send(
        exchange: HttpExchange,
        status: Int,
        body: JsonElement,
    ) {
        val bytes = body.toString().toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}
