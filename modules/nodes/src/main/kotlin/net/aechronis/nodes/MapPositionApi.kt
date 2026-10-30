package net.aechronis.nodes

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Serves `/api/me` so the web map can show the viewer's own player. The viewer
 * is matched by IP; if no player or more than one player is online from that IP,
 * nothing is returned. Listens on localhost only, nginx proxies to it and sets
 * `X-Real-IP`.
 */
object MapPositionApi {
    private var server: HttpServer? = null

    fun start(port: Int) {
        if (port <= 0) return
        server = try {
            // 127.0.0.1 rather than getLoopbackAddress(), which can be ::1
            HttpServer.create(InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), port), 0)
        } catch (e: IOException) {
            System.err.println("[Nodes] Map position API could not bind port $port: ${e.message}")
            return
        }
        server?.apply {
            createContext("/api/me", ::handle)
            // default executor runs every request on one thread, so one slow client stalls everyone
            executor = Executors.newVirtualThreadPerTaskExecutor()
            start()
        }
    }

    fun stop() {
        server?.stop(0)
        (server?.executor as? ExecutorService)?.shutdownNow()
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            exchange.responseHeaders.add("Cache-Control", "no-store")
            val player = viewerAddress(exchange)?.let(::playerFrom)
            if (player == null) {
                exchange.sendResponseHeaders(204, -1)
                return
            }
            val skin = skinHash(player)?.let { ""","skin":"$it"""" }.orEmpty()
            val body = """{"x":${player.position.x()},"z":${player.position.z()}$skin}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
        }
    }

    // ofLiteral never does a DNS lookup, and comparing InetAddress instead of
    // strings handles IPv6 written as "::1" by nginx but "0:0:0:0:0:0:0:1" by Java
    private fun viewerAddress(exchange: HttpExchange): InetAddress? {
        val header = exchange.requestHeaders.getFirst("X-Real-IP") ?: return exchange.remoteAddress.address
        return runCatching { InetAddress.ofLiteral(header) }.getOrNull()
    }

    private fun playerFrom(address: InetAddress): Player? {
        val overworld = MinecraftServer.getInstanceManager().instances.firstOrNull()
        return MinecraftServer.getConnectionManager().onlinePlayers
            .filter { (it.playerConnection.remoteAddress as? InetSocketAddress)?.address == address }
            .singleOrNull()
            ?.takeIf { it.instance == overworld }
    }

    // only the texture hash, so the map loads the skin from textures.minecraft.net
    // instead of a third-party head service that would learn which IP plays as whom
    private fun skinHash(player: Player): String? = runCatching {
        val payload = String(Base64.getDecoder().decode(player.skin!!.textures()))
        val url = Json.parseToJsonElement(payload).jsonObject["textures"]!!.jsonObject["SKIN"]!!.jsonObject["url"]!!.jsonPrimitive.content
        SKIN_URL.matchEntire(url)!!.groupValues[1]
    }.getOrNull()

    private val SKIN_URL = Regex("https?://textures\\.minecraft\\.net/texture/([0-9a-fA-F]{32,64})")
}
