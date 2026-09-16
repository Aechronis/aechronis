package net.aechronis.tebex

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
internal data class TebexPlayer(
    val id: Long,
    val name: String,
    val uuid: String,
)

@Serializable
internal data class QueueMeta(
    val execute_offline: Boolean,
    val next_check: Long,
)

@Serializable
internal data class TebexQueue(
    val meta: QueueMeta,
    val players: List<TebexPlayer>,
)

@Serializable
internal data class Conditions(
    val delay: Long = 0,
    val slots: Int = 0,
)

@Serializable
internal data class TebexCommand(
    val id: Long,
    val command: String,
    val conditions: Conditions = Conditions(),
    val player: TebexPlayer? = null,
)

@Serializable
internal data class CommandQueue(
    val commands: List<TebexCommand>,
)

internal class TebexApi(
    private val secret: String,
    private val endpoint: URI = URI.create("https://plugin.tebex.io"),
) : AutoCloseable {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun queue(): TebexQueue = json.decodeFromString(request("GET", "/queue"))

    fun commands(player: Long? = null): List<TebexCommand> {
        val path = if (player == null) "/queue/offline-commands" else "/queue/online-commands/$player"
        return json.decodeFromString<CommandQueue>(request("GET", path)).commands
    }

    fun acknowledge(ids: List<Long>) {
        request("DELETE", "/queue", json.encodeToString(mapOf("ids" to ids)))
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
    ): String {
        val request =
            HttpRequest
                .newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("X-Tebex-Secret", secret)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val expected = if (method == "DELETE") 204 else 200
        if (response.statusCode() != expected) {
            val retry =
                response
                    .headers()
                    .firstValue("Retry-After")
                    .orElse("300")
                    .toLongOrNull() ?: 300
            // Do not log response bodies, commands, or credentials.
            throw TebexApiException(response.statusCode(), retry.coerceAtLeast(60))
        }
        return response.body()
    }

    override fun close() = client.shutdownNow()
}

internal class TebexApiException(
    val status: Int,
    val retrySeconds: Long,
) : RuntimeException("Tebex API returned HTTP $status")
