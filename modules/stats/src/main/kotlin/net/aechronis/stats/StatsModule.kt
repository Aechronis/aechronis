package net.aechronis.stats

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Tracks kills/deaths here and cap stats from the nodes module, and serves them as JSON for the
 * website: GET /stats?sort=kills&player=Name, plus /stats/search and /stats/history. Put HTTPS in front (the site is https). Port: STATS_PORT.
 */
class StatsModule : AechronisModule {
    override val id = "stats"

    private var http: HttpServer? = null
    private var saver: ScheduledExecutorService? = null
    private var httpExecutor: java.util.concurrent.ExecutorService? = null
    private var playtimeTask: Task? = null

    override fun initialize(context: ModuleContext) {
        Stats.load()
        context.addListener(PlayerDeathEvent::class.java) { event ->
            val victim = event.player
            val killer = victim.lastDamageSource?.attacker as? Player
            // Team and ally kills count as neither a kill nor a death.
            if (killer != null && killer.uuid != victim.uuid && Stats.areFriendly(killer, victim)) return@addListener
            Stats.add(victim, Stat.DEATHS)
            if (killer != null && killer.uuid != victim.uuid) Stats.add(killer, Stat.KILLS)
        }
        // Playtime is credited on the server thread (nation lookups are not thread-safe) from the real time between ticks.
        var last = System.nanoTime()
        playtimeTask =
            ModuleScheduler
                .buildTask {
                    val now = System.nanoTime()
                    Stats.addPlaytime(MinecraftServer.getConnectionManager().onlinePlayers, TimeUnit.NANOSECONDS.toSeconds(now - last))
                    last = now
                }.delay(TaskSchedule.seconds(30))
                .repeat(TaskSchedule.seconds(30))
                .schedule()
        saver =
            Executors
                .newSingleThreadScheduledExecutor { Thread(it, "stats-save").also { t -> t.isDaemon = true } }
                .also { it.scheduleWithFixedDelay({ runCatching { Stats.save() } }, 30, 30, TimeUnit.SECONDS) }
        val port = System.getenv("STATS_PORT")?.toIntOrNull() ?: 8090
        try {
            http =
                HttpServer.create(InetSocketAddress(port), 0).apply {
                    createContext("/stats", ::handle)
                    createContext("/stats/search", ::handleSearch)
                    createContext("/stats/history", ::handleHistory)
                    httpExecutor = Executors.newFixedThreadPool(2) { Thread(it, "stats-http").also { t -> t.isDaemon = true } }
                    executor = httpExecutor
                    start()
                }
            println("[Stats] Serving /stats on port $port")
        } catch (error: Exception) {
            // Tracking still works without the API; don't take the server down over a busy port.
            println("[Stats] API disabled, could not bind port $port: $error")
        }
    }

    private fun handle(exchange: HttpExchange) =
        respond(exchange) { query ->
            cached("stats|${query["sort"]}|${query["player"]}|${query["type"]}|${query["scope"]}|${query["week"]}") {
                Stats.query(
                    query["sort"] ?: "kills",
                    20,
                    query["player"]?.take(16)?.lowercase(),
                    query["type"] == "nation",
                    query["scope"] == "war",
                    query["week"]?.also { w -> require(weekFormat.matches(w)) },
                )
            }
        }

    /** GET /stats/search?q=prefix: up to 8 matching players and nations. */
    private fun handleSearch(exchange: HttpExchange) =
        respond(exchange) { query ->
            val q = (query["q"] ?: "").trim().take(16).lowercase()
            if (q.isEmpty()) "{\"results\":[]}" else cached("search|$q") { Stats.search(q, 8) }
        }

    /** GET /stats/history?player=Name[&type=nation]: per war week stats, newest first. */
    private fun handleHistory(exchange: HttpExchange) =
        respond(exchange) { query ->
            val name = (query["player"] ?: "").trim().take(16).lowercase()
            require(name.isNotEmpty())
            val nation = query["type"] == "nation"
            cached("history|$nation|$name") { Stats.history(name, nation, 12) }
        }

    private fun respond(
        exchange: HttpExchange,
        handler: (Map<String, String>) -> String,
    ) = exchange.use {
        val query =
            (it.requestURI.rawQuery ?: "").split('&').filter { p -> p.isNotEmpty() }.associate { p ->
                val (k, v) = (p.split('=', limit = 2) + "")
                k to URLDecoder.decode(v, StandardCharsets.UTF_8)
            }
        val (status, body) =
            try {
                200 to handler(query)
            } catch (error: IllegalArgumentException) {
                400 to "{\"error\":\"bad request\"}"
            }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        it.responseHeaders.add("Content-Type", "application/json")
        it.responseHeaders.add("Access-Control-Allow-Origin", "*")
        it.responseHeaders.add("Cache-Control", "public, max-age=5")
        it.sendResponseHeaders(status, bytes.size.toLong())
        it.responseBody.write(bytes)
    }

    /** Serves identical requests from a 5 second cache so polling viewers never re-sort the whole player list. */
    private val weekFormat = Regex("""\d{4}-\d{2}-\d{2}(~\d+)?""")

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    private fun cached(
        key: String,
        compute: () -> String,
    ): String {
        val now = System.currentTimeMillis()
        cache[key]?.let { (at, body) -> if (now - at < 5_000) return body }
        val body = compute()
        if (cache.size > 200) cache.clear() // bounded: names are user-supplied
        cache[key] = now to body
        return body
    }

    override fun prepareForShutdown(context: ModuleContext) = stop()

    override fun shutdown(context: ModuleContext) = stop()

    private fun stop() {
        http?.stop(0)
        http = null
        httpExecutor?.shutdownNow()
        httpExecutor = null
        playtimeTask?.cancel()
        playtimeTask = null
        saver?.shutdownNow()
        saver = null
        Stats.save()
    }
}
